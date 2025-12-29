package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming geometry writer: Streams quad data to a paged temporary file.
 * Implements "Virtual Page Allocator" strategy:
 * - Data is buffered per-material (Buckets).
 * - When a bucket fills (64KB), it is flushed to the temp file as a Page.
 * - This ensures high write throughput (append-only) and fast read-back (bulk reads).
 * 
 * Page Format (Interleaved, 204 bytes per quad):
 * - Geometry (140 bytes): [Hash(4), Sprite(4), Overlay(4), Flags(4), Pos(48), Norm(12), Color(64)]
 * - UV (64 bytes): [UV0(32), UV1(32)]
 */
final class StreamingGeometryWriter implements AutoCloseable {
    
    // Page size: 64KB (approx 321 quads).
    // Small enough to keep memory low with many materials, large enough for efficient IO.
    private static final int PAGE_SIZE = 64 * 1024; 
    private static final int BYTES_PER_QUAD = 204; // 140 geo + 64 uv

    private final FileChannel tempChannel;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;
    
    // Active buckets for each material/animation group
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    private boolean closed = false;

    // Best-effort cleaner for direct buffers to release native memory without waiting for GC
    private static final Object UNSAFE;
    private static final Method INVOKE_CLEANER;
    static {
        Object unsafe = null;
        Method invokeCleaner = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
        } catch (Throwable ignored) {}
        UNSAFE = unsafe;
        INVOKE_CLEANER = invokeCleaner;
    }

    StreamingGeometryWriter(Path tempFile, Path unusedUvPath, SpriteIndex spriteIndex, GeometryIndex geometryIndex) throws IOException {
        this.spriteIndex = spriteIndex;
        this.geometryIndex = geometryIndex;

        // Use a single temporary file for all paged data
        this.tempChannel = FileChannel.open(tempFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);

        VoxelBridgeLogger.info(LogModule.GLTF, "[StreamingWriter] Initialized Paged Writer");
        VoxelBridgeLogger.info(LogModule.GLTF, "[StreamingWriter] Temp file: " + tempFile);
    }

    /**
     * Inner class representing a write buffer for a specific material group.
     */
    private static class Bucket {
        final ByteBuffer buffer;
        final Set<String> usedSprites = ConcurrentHashMap.newKeySet();
        int quadCount = 0;
        boolean doubleSided = false;

        Bucket() {
            this.buffer = ByteBuffer.allocateDirect(PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /**
     * Writes a single quad from flat arrays (optimization to avoid small object allocation).
     */
    synchronized long writeQuadFlat(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        float[] flatPositions, int posOffset,
        float[] flatUv0, int uv0Offset,
        float[] flatUv1, int uv1Offset,
        float[] flatNormal, int normOffset,
        float[] flatColors, int colOffset,
        boolean doubleSided
    ) throws IOException {
        if (closed) {
            throw new IllegalStateException("Writer is closed");
        }

        // Get sprite IDs
        int spriteId = spriteIndex.getId(spriteKey);
        int overlaySpriteId = overlaySpriteKey != null ? spriteIndex.getId(overlaySpriteKey) : -1;

        // Get current quad offset (logical index, still useful for debugging/stats)
        long logicalOffset = spriteIndex.nextQuadOffset();
        spriteIndex.recordUsage(spriteKey, 0xFFFFFF, logicalOffset);

        // Get or create bucket
        Bucket bucket = buckets.computeIfAbsent(materialGroupKey, k -> new Bucket());

        // Check if bucket has space
        if (bucket.buffer.remaining() < BYTES_PER_QUAD) {
            flushBucket(materialGroupKey, bucket);
        }

        // Write data to bucket (Interleaved)
        ByteBuffer buf = bucket.buffer;
        
        // --- Geometry Part (140 bytes) ---
        buf.putInt(materialGroupKey.hashCode());
        buf.putInt(spriteId);
        buf.putInt(overlaySpriteId);
        buf.putInt(doubleSided ? 1 : 0); // Flags (using int for alignment/padding simplified)
        
        // Pos (48)
        for (int i = 0; i < 12; i++) buf.putFloat(flatPositions[posOffset + i]);
        // Norm (12)
        if (flatNormal != null) {
            for (int i = 0; i < 3; i++) buf.putFloat(flatNormal[normOffset + i]);
        } else {
            buf.putFloat(0f); buf.putFloat(1f); buf.putFloat(0f);
        }
        // Color (64)
        for (int i = 0; i < 16; i++) buf.putFloat(flatColors[colOffset + i]);

        // --- UV Part (64 bytes) ---
        // UV0 (32)
        for (int i = 0; i < 8; i++) buf.putFloat(flatUv0[uv0Offset + i]);
        // UV1 (32)
        if (flatUv1 != null) {
            for (int i = 0; i < 8; i++) buf.putFloat(flatUv1[uv1Offset + i]);
        } else {
            for (int i = 0; i < 8; i++) buf.putFloat(0f);
        }

        // Update bucket tracking
        bucket.quadCount++;
        bucket.usedSprites.add(spriteKey);
        if (overlaySpriteKey != null) bucket.usedSprites.add(overlaySpriteKey);
        if (doubleSided) bucket.doubleSided = true;

        return logicalOffset;
    }

    private void flushBucket(String materialKey, Bucket bucket) throws IOException {
        if (bucket.quadCount == 0) return;

        bucket.buffer.flip();
        int bytesToWrite = bucket.buffer.limit();
        
        // Atomic write to end of channel
        long fileOffset = tempChannel.size();
        while (bucket.buffer.hasRemaining()) {
            tempChannel.write(bucket.buffer);
        }
        
        // Record page info
        geometryIndex.recordPage(
            materialKey,
            bucket.usedSprites,
            fileOffset,
            bucket.quadCount,
            bucket.doubleSided
        );

        // Reset bucket
        bucket.buffer.clear();
        bucket.quadCount = 0;
        bucket.usedSprites.clear();
        // Keep doubleSided flag? Usually resets, but MaterialChunk merges it anyway.
        // Better to reset for next page accuracy.
        bucket.doubleSided = false; 
    }

    /**
     * Legacy single quad write support.
     */
    synchronized long writeQuad(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) throws IOException {
        return writeQuadFlat(
            materialGroupKey, spriteKey, overlaySpriteKey,
            positions, 0,
            uv0, 0,
            uv1, 0,
            normal, 0,
            colors, 0,
            doubleSided
        );
    }

    /**
     * Finalizes the write and flushes buffers
     */
    void finalizeWrite() throws IOException {
        if (closed) return;

        VoxelBridgeLogger.info(LogModule.GLTF, "[StreamingWriter] Flushing all buckets...");
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            flushBucket(entry.getKey(), entry.getValue());
        }
        buckets.clear();

        long totalQuads = spriteIndex.getTotalQuadCount();
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[StreamingWriter] Finalized. Total quads: %d", totalQuads));
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[StreamingWriter] Temp file size: %.2f MB", tempChannel.size() / 1024.0 / 1024.0));

        // Explicitly free direct buffers to avoid lingering native memory until GC
        buckets.values().forEach(bucket -> cleanDirect(bucket.buffer));
        buckets.clear();
    }

    SpriteIndex getSpriteIndex() {
        return spriteIndex;
    }

    GeometryIndex getGeometryIndex() {
        return geometryIndex;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        finalizeWrite();
        tempChannel.close();

        // In case finalizeWrite was skipped or threw, ensure buffers are freed
        buckets.values().forEach(bucket -> cleanDirect(bucket.buffer));
        buckets.clear();

        VoxelBridgeLogger.info(LogModule.GLTF, "[StreamingWriter] Closed");
    }

    private static void cleanDirect(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        if (UNSAFE == null || INVOKE_CLEANER == null) return;
        try {
            INVOKE_CLEANER.invoke(UNSAFE, buffer);
        } catch (Throwable ignored) {}
    }
}


