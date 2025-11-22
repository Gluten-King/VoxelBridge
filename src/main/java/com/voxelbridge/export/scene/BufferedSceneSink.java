package com.voxelbridge.export.scene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary sink that buffers quads in memory.
 * Used to ensure chunk exports are atomic: either full chunk is written or nothing (on retry).
 * This prevents partial writes, duplicate geometry on retries, and boundary artifacts.
 */
public final class BufferedSceneSink implements SceneSink {

    private final List<QuadRecord> buffer = new ArrayList<>();

    @Override
    public void addQuad(String spriteKey,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] uv2,
                        float[] uv3,
                        float[] normal,
                        float[] colors,
                        boolean doubleSided) {
        // We capture the data. Arrays should be cloned if the producer reuses them.
        // BlockExporter creates new arrays for positions/uvs per quad, but to be safe and immutable:
        buffer.add(new QuadRecord(
            spriteKey,
            positions, // Assumed safe to store directly as BlockExporter creates new arrays
            uv0,
            uv1,
            uv2,
            uv3,
            normal,
            colors,
            doubleSided
        ));
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        throw new UnsupportedOperationException("Buffered sink cannot write to file directly. Use flushTo().");
    }

    /**
     * Flushes all buffered quads to the target sink.
     * This should only be called once the chunk is confirmed valid.
     */
    public void flushTo(SceneSink target) {
        for (QuadRecord q : buffer) {
            target.addQuad(
                q.spriteKey,
                q.positions,
                q.uv0,
                q.uv1,
                q.uv2,
                q.uv3,
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

    // Helper record to hold quad data in memory
    private record QuadRecord(
        String spriteKey,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] uv2,
        float[] uv3,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) {}
}