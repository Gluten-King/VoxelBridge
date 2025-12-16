package com.voxelbridge.export.scene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 区块级缓冲Sink：缓冲chunk的所有quad，并在flush时进行去重。
 * 去重在chunk级别按material分组进行，接受chunk边界的顶点重复。
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
     * 将缓冲的quads按material分组去重后flush到目标sink
     * 去重在chunk级别进行（接受chunk边界的顶点重复）
     */
    public void flushTo(SceneSink target) {
        if (buffer.isEmpty()) {
            return;
        }

        // 按material分组
        Map<String, List<QuadRecord>> byMaterial = new HashMap<>();
        for (QuadRecord quad : buffer) {
            byMaterial.computeIfAbsent(quad.materialGroupKey, k -> new ArrayList<>()).add(quad);
        }

        // 对每个material进行去重处理
        for (Map.Entry<String, List<QuadRecord>> entry : byMaterial.entrySet()) {
            String materialKey = entry.getKey();
            List<QuadRecord> quads = entry.getValue();

            // 创建chunk级去重器
            ChunkDeduplicator deduper = new ChunkDeduplicator(materialKey);

            // 处理所有quads
            for (QuadRecord quad : quads) {
                deduper.processQuad(quad);
            }

            // flush去重后的数据
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

    // 内部数据结构（package-visible for ChunkDeduplicator）
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
