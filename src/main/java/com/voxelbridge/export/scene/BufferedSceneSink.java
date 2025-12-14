package com.voxelbridge.export.scene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary sink that buffers quads in memory.
 * Used to ensure chunk exports are atomic: either full chunk is written or nothing (on retry).
 */
public final class BufferedSceneSink implements SceneSink {

    // OPTIMIZATION: Pre-allocate capacity based on average quads per chunk (~8000)
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
        // OPTIMIZATION: Store array references directly instead of cloning.
        // Safe because:
        // 1. Arrays are created fresh in each BlockExporter loop iteration
        // 2. BufferedSceneSink is flushed immediately after chunk export completes
        // 3. Arrays are not reused across chunks
        // Memory savings: ~1.7GB for large exports (eliminates 5 clones per quad)
        buffer.add(new QuadRecord(
            materialGroupKey,
            spriteKey,
            overlaySpriteKey,
            positions,  // Direct reference instead of positions.clone()
            uv0,        // Direct reference instead of uv0.clone()
            uv1,        // Direct reference instead of uv1.clone()
            normal,     // Direct reference instead of normal.clone()
            colors,     // Direct reference instead of colors.clone()
            doubleSided
        ));
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        throw new UnsupportedOperationException("Buffered sink cannot write to file directly. Use flushTo().");
    }

    public void flushTo(SceneSink target) {
        for (QuadRecord q : buffer) {
            target.addQuad(
                q.materialGroupKey,
                q.spriteKey,
                q.overlaySpriteKey,
                q.positions,
                q.uv0,
                q.uv1,
                q.normal,
                q.colors,
                q.doubleSided
            );
        }
        buffer.clear();
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public int getQuadCount() {
        return buffer.size();
    }

    private record QuadRecord(
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
