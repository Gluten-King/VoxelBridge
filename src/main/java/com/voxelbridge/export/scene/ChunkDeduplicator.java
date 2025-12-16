package com.voxelbridge.export.scene;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.util.*;

/**
 * Chunk级别的去重器：对单个chunk内的quads按material分组进行顶点去重。
 *
 * 设计原则：
 * 1. 接受chunk边界的顶点重复（不做跨chunk去重）
 * 2. 每个chunk独立去重，处理完立即释放内存
 * 3. 按material分组去重，提高去重效率
 * 4. 复用PrimitiveData的去重逻辑（VertexKey + 量化）
 */
final class ChunkDeduplicator {
    private final String materialKey;
    private final List<Float> positions;
    private final List<Float> uv0;
    private final List<Float> uv1;
    private final List<Float> colors;

    // 顶点去重查找表
    private final TObjectIntHashMap<VertexKey> vertexLookup;

    // Quad级去重（仅透明材质）
    private final Set<QuadKey> quadKeys;
    private final boolean needsQuadDedup;

    // 去重后的quads
    private final List<DeduplicatedQuad> quads;
    private int vertexCount = 0;

    /**
     * 去重后的quad数据
     */
    record DeduplicatedQuad(
        String spriteKey,
        String overlaySpriteKey,
        int[] vertexIndices,  // 4个顶点在去重后数组中的索引
        float[] normal,
        boolean doubleSided
    ) {}

    ChunkDeduplicator(String materialKey) {
        this.materialKey = materialKey;
        this.positions = new ArrayList<>(1000 * 3);
        this.uv0 = new ArrayList<>(1000 * 2);
        this.uv1 = new ArrayList<>(1000 * 2);
        this.colors = new ArrayList<>(1000 * 4);
        this.vertexLookup = new TObjectIntHashMap<>(1000, 0.5f, -1);
        this.quads = new ArrayList<>(500);

        // Quad去重仅用于透明材质（防止Z-fighting）
        this.needsQuadDedup = isTransparentMaterial(materialKey);
        this.quadKeys = needsQuadDedup ? new HashSet<>() : null;
    }

    /**
     * 处理一个quad，进行顶点去重
     */
    void processQuad(BufferedSceneSink.QuadRecord quad) {
        int[] order = sortQuadCCW(quad.positions());
        int spriteHash = Objects.hash(quad.spriteKey(), quad.overlaySpriteKey());

        // 暂存待提交的顶点
        List<PendingVertex> pending = new ArrayList<>(4);
        int[] verts = new int[4];

        // 对4个顶点进行去重检查
        for (int i = 0; i < 4; i++) {
            int oi = order[i];
            float[] pos = quad.positions();
            float[] uv = quad.uv0();
            float[] uv1Array = quad.uv1();
            float[] col = quad.colors();

            VertexKey key = new VertexKey(
                spriteHash,
                quantize(pos[oi * 3]), quantize(pos[oi * 3 + 1]), quantize(pos[oi * 3 + 2]),
                quantizeUV(uv[oi * 2]), quantizeUV(uv[oi * 2 + 1]),
                quantizeUV(uv1Array != null ? uv1Array[oi * 2] : 0f),
                quantizeUV(uv1Array != null ? uv1Array[oi * 2 + 1] : 0f),
                quantizeColor(col[oi * 4]), quantizeColor(col[oi * 4 + 1]),
                quantizeColor(col[oi * 4 + 2]), quantizeColor(col[oi * 4 + 3])
            );

            int existing = vertexLookup.get(key);
            if (existing != -1) {
                // 顶点已存在，复用
                verts[i] = existing;
            } else {
                // 新顶点，加入待提交列表
                verts[i] = vertexCount + pending.size();
                pending.add(new PendingVertex(
                    key,
                    pos[oi * 3], pos[oi * 3 + 1], pos[oi * 3 + 2],
                    uv[oi * 2], uv[oi * 2 + 1],
                    uv1Array != null ? uv1Array[oi * 2] : 0f,
                    uv1Array != null ? uv1Array[oi * 2 + 1] : 0f,
                    col[oi * 4], col[oi * 4 + 1], col[oi * 4 + 2], col[oi * 4 + 3]
                ));
            }
        }

        // 检查退化quad（顶点重复）
        if (verts[0] == verts[1] || verts[1] == verts[2] ||
            verts[2] == verts[3] || verts[0] == verts[3]) {
            return;
        }

        // Quad级去重（仅透明材质）
        if (needsQuadDedup) {
            QuadKey qk = QuadKey.from(verts[0], verts[1], verts[2], verts[3]);
            if (!quadKeys.add(qk)) {
                return; // 重复quad，跳过
            }
        }

        // 提交新顶点
        for (PendingVertex pv : pending) {
            int idx = vertexCount++;
            vertexLookup.put(pv.key(), idx);
            positions.add(pv.px()); positions.add(pv.py()); positions.add(pv.pz());
            uv0.add(pv.u()); uv0.add(pv.v());
            uv1.add(pv.u1()); uv1.add(pv.v1());
            colors.add(pv.r()); colors.add(pv.g()); colors.add(pv.b()); colors.add(pv.a());
        }

        // 保存去重后的quad
        quads.add(new DeduplicatedQuad(
            quad.spriteKey(),
            quad.overlaySpriteKey(),
            verts,
            quad.normal(),
            quad.doubleSided()
        ));
    }

    /**
     * 将去重后的数据flush到目标sink
     */
    void flushTo(SceneSink target) {
        if (quads.isEmpty()) return;

        for (DeduplicatedQuad quad : quads) {
            // 重建完整的顶点数据
            float[] quadPositions = new float[12];
            float[] quadUv0 = new float[8];
            float[] quadUv1 = new float[8];
            float[] quadColors = new float[16];

            for (int i = 0; i < 4; i++) {
                int vertIdx = quad.vertexIndices()[i];

                // positions (3 floats per vertex)
                quadPositions[i * 3] = positions.get(vertIdx * 3);
                quadPositions[i * 3 + 1] = positions.get(vertIdx * 3 + 1);
                quadPositions[i * 3 + 2] = positions.get(vertIdx * 3 + 2);

                // uv0 (2 floats per vertex)
                quadUv0[i * 2] = uv0.get(vertIdx * 2);
                quadUv0[i * 2 + 1] = uv0.get(vertIdx * 2 + 1);

                // uv1 (2 floats per vertex)
                quadUv1[i * 2] = uv1.get(vertIdx * 2);
                quadUv1[i * 2 + 1] = uv1.get(vertIdx * 2 + 1);

                // colors (4 floats per vertex)
                quadColors[i * 4] = colors.get(vertIdx * 4);
                quadColors[i * 4 + 1] = colors.get(vertIdx * 4 + 1);
                quadColors[i * 4 + 2] = colors.get(vertIdx * 4 + 2);
                quadColors[i * 4 + 3] = colors.get(vertIdx * 4 + 3);
            }

            target.addQuad(
                materialKey,
                quad.spriteKey(),
                quad.overlaySpriteKey(),
                quadPositions,
                quadUv0,
                quadUv1,
                quad.normal(),
                quadColors,
                quad.doubleSided()
            );
        }
    }

    int getVertexCount() {
        return vertexCount;
    }

    int getQuadCount() {
        return quads.size();
    }

    // ==================== 辅助方法 ====================

    private static boolean isTransparentMaterial(String materialKey) {
        if (materialKey == null) return false;
        String lower = materialKey.toLowerCase();
        return lower.contains("glass") || lower.contains("leaves") ||
               lower.contains("water") || lower.contains("ice") ||
               lower.contains("slime") || lower.contains("honey") ||
               lower.contains("portal") || lower.contains("stained_glass");
    }

    private int quantize(float v) { return Math.round(v * 10000f); }
    private int quantizeUV(float v) { return Math.round(v * 100000f); }
    private int quantizeColor(float v) { return Math.round(v * 100f); }

    /**
     * 顶点键（用于去重）
     */
    private record VertexKey(
        int spriteHash, int px, int py, int pz,
        int u, int v, int u1, int v1,
        int r, int g, int b, int a
    ) {}

    /**
     * 待提交顶点
     */
    private record PendingVertex(
        VertexKey key,
        float px, float py, float pz,
        float u, float v,
        float u1, float v1,
        float r, float g, float b, float a
    ) {}

    /**
     * Quad键（用于quad级去重）
     */
    private record QuadKey(int a, int b, int c, int d) {
        static QuadKey from(int v0, int v1, int v2, int v3) {
            int[] arr = new int[]{v0, v1, v2, v3};
            Arrays.sort(arr);
            return new QuadKey(arr[0], arr[1], arr[2], arr[3]);
        }
    }

    /**
     * 排序quad顶点为CCW顺序
     */
    private int[] sortQuadCCW(float[] pos) {
        Integer[] idx = {0, 1, 2, 3};

        // 计算法线
        float ax = pos[3] - pos[0], ay = pos[4] - pos[1], az = pos[5] - pos[2];
        float bx = pos[6] - pos[0], by = pos[7] - pos[1], bz = pos[8] - pos[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);

        // 选择投影平面
        int drop = (anx >= any && anx >= anz) ? 0 : (any >= anz ? 1 : 2);

        // 计算中心点
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += pos[i * 3];
            cy += pos[i * 3 + 1];
            cz += pos[i * 3 + 2];
        }
        final float fcx = cx * 0.25f, fcy = cy * 0.25f, fcz = cz * 0.25f;
        final int fdrop = drop;

        // 按角度排序
        Arrays.sort(idx, (i1, i2) -> {
            float x1 = pos[i1 * 3] - fcx, y1 = pos[i1 * 3 + 1] - fcy, z1 = pos[i1 * 3 + 2] - fcz;
            float x2 = pos[i2 * 3] - fcx, y2 = pos[i2 * 3 + 1] - fcy, z2 = pos[i2 * 3 + 2] - fcz;
            double a1 = (fdrop == 0) ? Math.atan2(z1, y1) : (fdrop == 1) ? Math.atan2(z1, x1) : Math.atan2(y1, x1);
            double a2 = (fdrop == 0) ? Math.atan2(z2, y2) : (fdrop == 1) ? Math.atan2(z2, x2) : Math.atan2(y2, x2);
            return Double.compare(a1, a2);
        });

        // 检查绕序方向
        float ovx1 = pos[3] - pos[0], ovy1 = pos[4] - pos[1], ovz1 = pos[5] - pos[2];
        float ovx2 = pos[6] - pos[0], ovy2 = pos[7] - pos[1], ovz2 = pos[8] - pos[2];
        float onx = ovy1 * ovz2 - ovz1 * ovy2, ony = ovz1 * ovx2 - ovx1 * ovz2, onz = ovx1 * ovy2 - ovy1 * ovx2;

        float svx1 = pos[idx[1] * 3] - pos[idx[0] * 3];
        float svy1 = pos[idx[1] * 3 + 1] - pos[idx[0] * 3 + 1];
        float svz1 = pos[idx[1] * 3 + 2] - pos[idx[0] * 3 + 2];
        float svx2 = pos[idx[2] * 3] - pos[idx[0] * 3];
        float svy2 = pos[idx[2] * 3 + 1] - pos[idx[0] * 3 + 1];
        float svz2 = pos[idx[2] * 3 + 2] - pos[idx[0] * 3 + 2];
        float snx = svy1 * svz2 - svz1 * svy2, sny = svz1 * svx2 - svx1 * svz2, snz = svx1 * svy2 - svy1 * svx2;

        if (onx * snx + ony * sny + onz * snz < 0) {
            int tmp = idx[1];
            idx[1] = idx[3];
            idx[3] = tmp;
        }

        return new int[]{idx[0], idx[1], idx[2], idx[3]};
    }
}
