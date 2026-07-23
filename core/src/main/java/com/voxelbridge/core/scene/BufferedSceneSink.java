package com.voxelbridge.core.scene;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.QuadSemantic;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.core.ir.TintMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk-level buffered sink: buffers all quads for a chunk and deduplicates on flush.
 * Dedup is per-chunk and per-material; duplicate vertices on chunk borders are allowed.
 */
public final class BufferedSceneSink implements IrSink {

    private static final int ESTIMATED_QUADS_PER_CHUNK = 8000;
    private final List<QuadRecord> buffer = new ArrayList<>(ESTIMATED_QUADS_PER_CHUNK);

    @Override
    public void addQuad(String materialKey,
                        String spriteKey,
                        String overlaySpriteKey,
                        QuadSemantic semantic,
                        RenderLayer renderLayer,
                        TintMode tintMode,
                        boolean doubleSided,
                        boolean emissive,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] lightUv,
                        float[] midBlock,
                        float[] normal,
                        float[] colors) {
        buffer.add(new QuadRecord(
            materialKey,
            spriteKey,
            overlaySpriteKey,
            semantic,
            renderLayer,
            tintMode,
            emissive,
            positions,
            uv0,
            uv1,
            lightUv,
            midBlock,
            normal,
            colors,
            doubleSided
        ));
    }

    @Override
    public void onChunkStart(int chunkX, int chunkZ) {
        // No-op; buffering is per-chunk and managed by caller.
    }

    @Override
    public void onChunkEnd(int chunkX, int chunkZ, boolean successful) {
        // No-op; buffering is per-chunk and managed by caller.
    }

    /**
     * Flush buffered quads to the target IR sink after per-material dedup.
     */
    public void flushTo(IrSink target) {
        if (buffer.isEmpty()) {
            return;
        }

        Map<String, List<QuadRecord>> byMaterial = new HashMap<>();
        for (QuadRecord quad : buffer) {
            byMaterial.computeIfAbsent(quad.materialGroupKey, k -> new ArrayList<>()).add(quad);
        }

        for (Map.Entry<String, List<QuadRecord>> entry : byMaterial.entrySet()) {
            String materialKey = entry.getKey();
            List<QuadRecord> quads = entry.getValue();

            ChunkDeduplicator deduper = new ChunkDeduplicator(materialKey);
            for (QuadRecord quad : quads) {
                deduper.processQuad(quad);
            }
            deduper.flushTo(target);
        }

        buffer.clear();
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public int getQuadCount() {
        return buffer.size();
    }

    // Internal data structure (package-visible for ChunkDeduplicator).
    record QuadRecord(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        QuadSemantic semantic,
        RenderLayer renderLayer,
        TintMode tintMode,
        boolean emissive,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] lightUv,
        float[] midBlock,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) {}
}
