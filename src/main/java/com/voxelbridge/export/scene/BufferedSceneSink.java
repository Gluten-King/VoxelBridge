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

    private final List<QuadRecord> buffer = new ArrayList<>();

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
