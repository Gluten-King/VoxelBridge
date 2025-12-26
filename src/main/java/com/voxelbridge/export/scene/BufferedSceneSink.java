package com.voxelbridge.export.scene;

import java.nio.file.Path;
import java.util.*;

/**
 * Chunk-level buffered sink: buffers all quads for a chunk and deduplicates on flush.
 * Dedup is per-chunk and per-material; duplicate vertices on chunk borders are allowed.
 */
public final class BufferedSceneSink implements SceneSink {

    private static final int ESTIMATED_QUADS_PER_CHUNK = 8000;
    private final List<QuadRecord> buffer = new ArrayList<>(ESTIMATED_QUADS_PER_CHUNK);

    @Override
    public void addQuad(String materialGroupKey,
                        String spriteKey,
                        String overlaySpriteKey,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] normal,
                        float[] colors,
                        boolean doubleSided) {
        buffer.add(new QuadRecord(
            materialGroupKey,
            spriteKey,
            overlaySpriteKey,
            positions,
            uv0,
            uv1,
            normal,
            colors,
            doubleSided
        ));
    }

    @Override
    public Path write(SceneWriteRequest request) {
        throw new UnsupportedOperationException("Buffered sink cannot write to file directly. Use flushTo().");
    }

    /**
     * Flush buffered quads to the target sink after per-material dedup.
     * Dedup is per-chunk (allows duplicates across chunk borders).
     */
    public void flushTo(SceneSink target) {
        if (buffer.isEmpty()) {
            return;
        }

        // Group by material.
        Map<String, List<QuadRecord>> byMaterial = new HashMap<>();
        for (QuadRecord quad : buffer) {
            byMaterial.computeIfAbsent(quad.materialGroupKey, k -> new ArrayList<>()).add(quad);
        }

        // Deduplicate each material group.
        for (Map.Entry<String, List<QuadRecord>> entry : byMaterial.entrySet()) {
            String materialKey = entry.getKey();
            List<QuadRecord> quads = entry.getValue();

            // Create per-chunk deduplicator.
            ChunkDeduplicator deduper = new ChunkDeduplicator(materialKey);

            // Process all quads.
            for (QuadRecord quad : quads) {
                deduper.processQuad(quad);
            }

            // Flush deduplicated data.
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
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) {}
}
