package com.voxelbridge.export.scene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 区块级缓冲Sink：缓冲chunk的所有quad。
 * 不进行去重 - 去重在glTF组装阶段按material全局进行。
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
    public Path write(SceneWriteRequest request) throws IOException {
        throw new UnsupportedOperationException("Buffered sink cannot write to file directly. Use flushTo().");
    }

    /**
     * 将缓冲的quads直接flush到目标sink（不去重）
     * 去重将在glTF组装阶段按material全局进行
     */
    public void flushTo(SceneSink target) {
        if (buffer.isEmpty()) {
            return;
        }

        // 直接flush，不去重（全局去重在组装阶段完成）
        for (QuadRecord quad : buffer) {
            target.addQuad(
                quad.materialGroupKey,
                quad.spriteKey,
                quad.overlaySpriteKey,
                quad.positions,
                quad.uv0,
                quad.uv1,
                quad.normal,
                quad.colors,
                quad.doubleSided
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

    // 内部数据结构
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
