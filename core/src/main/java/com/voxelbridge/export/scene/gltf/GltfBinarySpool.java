package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** Owns the producer/consumer queue and temporary geometry spool lifecycle. */
final class GltfBinarySpool {
    private static final Object POISON = new Object();

    private final ExportState state;
    private final ExportOptions options;
    private final StreamingGeometryWriter writer;
    private final BiFunction<String, String, String> bucketResolver;
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(4096);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private Thread writerThread;

    GltfBinarySpool(ExportState state,
                    ExportOptions options,
                    StreamingGeometryWriter writer,
                    BiFunction<String, String, String> bucketResolver) {
        this.state = state;
        this.options = options;
        this.writer = writer;
        this.bucketResolver = bucketResolver;
    }

    void addQuad(String bucketKey,
                 String spriteKey,
                 String overlaySpriteKey,
                 int flags,
                 float[] positions,
                 float[] uv0,
                 float[] uv1,
                 float[] normal,
                 float[] colors) {
        start();
        if (options.colorMode() != null && options.colorMode().usesColormap()
            && (uv1 == null || uv1.length < 8)) {
            uv1 = whiteColormapUv();
        }
        enqueue(new QuadBatch(
            bucketKey, spriteKey, overlaySpriteKey, flags,
            positions, uv0, uv1, normal, colors));
    }

    void addBatch(String materialGroupKey,
                  List<String> spriteKeys,
                  List<String> overlaySpriteKeys,
                  float[] flatPositions,
                  float[] flatUv0,
                  float[] flatUv1,
                  float[] flatNormals,
                  float[] flatColors,
                  int[] flags) {
        start();
        enqueue(new BulkBatch(
            materialGroupKey, spriteKeys, overlaySpriteKeys,
            flatPositions, flatUv0, flatUv1, flatNormals, flatColors, flags));
    }

    void finish() throws IOException {
        start();
        enqueue(POISON);
        try {
            writerThread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Export interrupted during writer thread join", interrupted);
        }
        throwIfFailed();
        writer.finalizeWrite();
    }

    private void start() {
        if (started.getAndSet(true)) return;
        writerThread = new Thread(this::runWriter, "VoxelBridge-StreamingWriter");
        writerThread.start();
    }

    private void runWriter() {
        try {
            while (true) {
                Object item = queue.take();
                if (item == POISON) return;
                if (item instanceof QuadBatch batch) write(batch);
                else if (item instanceof BulkBatch batch) write(batch);
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
            VoxelBridgeLogger.error(LogModule.GLTF,
                "[GltfBuilder][ERROR] Writer thread failed: " + throwable.getMessage());
        }
    }

    private void write(QuadBatch batch) throws IOException {
        writer.writeQuad(
            batch.bucketKey(), batch.spriteKey(), batch.overlaySpriteKey(), batch.flags(),
            batch.positions(), batch.uv0(), batch.uv1(), batch.normal(), batch.colors());
    }

    private void write(BulkBatch batch) throws IOException {
        int count = batch.spriteKeys().size();
        float[] defaultUv1 = defaultUv1(batch.flatUv1());
        for (int index = 0; index < count; index++) {
            float[] selectedUv1 = defaultUv1 != null ? defaultUv1 : batch.flatUv1();
            int uv1Offset = defaultUv1 != null ? 0 : index * 8;
            if (selectedUv1 == null) uv1Offset = 0;
            int flags = batch.flags() != null && index < batch.flags().length
                ? batch.flags()[index]
                : 0;
            String spriteKey = batch.spriteKeys().get(index);
            writer.writeQuadFlat(
                bucketResolver.apply(batch.materialGroupKey(), spriteKey),
                spriteKey,
                batch.overlaySpriteKeys().get(index),
                flags,
                batch.flatPositions(), index * 12,
                batch.flatUv0(), index * 8,
                selectedUv1, uv1Offset,
                batch.flatNormals(), index * 3,
                batch.flatColors(), index * 16);
        }
    }

    private float[] defaultUv1(float[] supplied) {
        if (options.colorMode() == null || !options.colorMode().usesColormap()
            || supplied != null && supplied.length > 0) return null;
        return whiteColormapUv();
    }

    private float[] whiteColormapUv() {
        float[] lut = ColorMapManager.remapColorUV(state, 0xffffffff, options.atlasSize());
        return new float[] {
            lut[0], lut[1], lut[2], lut[1],
            lut[2], lut[3], lut[0], lut[3]
        };
    }

    private void enqueue(Object item) {
        while (true) {
            Throwable writerFailure = failure.get();
            if (writerFailure != null) {
                throw new IllegalStateException("glTF writer thread failed", writerFailure);
            }
            try {
                if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while enqueueing glTF geometry", interrupted);
            }
        }
    }

    private void throwIfFailed() throws IOException {
        Throwable writerFailure = failure.get();
        if (writerFailure != null) throw new IOException("glTF writer thread failed", writerFailure);
    }

    private record QuadBatch(String bucketKey,
                             String spriteKey,
                             String overlaySpriteKey,
                             int flags,
                             float[] positions,
                             float[] uv0,
                             float[] uv1,
                             float[] normal,
                             float[] colors) {}

    private record BulkBatch(String materialGroupKey,
                             List<String> spriteKeys,
                             List<String> overlaySpriteKeys,
                             float[] flatPositions,
                             float[] flatUv0,
                             float[] flatUv1,
                             float[] flatNormals,
                             float[] flatColors,
                             int[] flags) {}
}
