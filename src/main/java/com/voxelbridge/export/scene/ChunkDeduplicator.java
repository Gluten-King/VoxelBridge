package com.voxelbridge.export.scene;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.util.*;

/**
 * Chunk-level deduplicator: per-material vertex dedup within a chunk.
 * Allows duplicates across chunk borders and frees memory after flush.
 */
final class ChunkDeduplicator {

    private final String materialKey;
    private final List<Float> positions;
    private final List<Float> uv0;
    private final List<Float> uv1;
    private final List<Float> colors;

    // Vertex dedup lookup table.
    private final TObjectIntHashMap<VertexKey> vertexLookup;

    // Quad-level dedup (transparent materials only).
    private final Set<QuadKey> quadKeys;
    private final boolean needsQuadDedup;

    // Deduplicated quads.
    private final List<DeduplicatedQuad> quads;
    private int vertexCount = 0;

    // Reusable buffer for processing a single quad (4 vertices).
    private final TempVertex[] quadBuffer;

    // Deduplicated quad data.
    record DeduplicatedQuad(
        String spriteKey,
        String overlaySpriteKey,
        int[] vertexIndices,  // Indices of the 4 vertices in the deduped arrays.
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

        // Quad dedup only for transparent materials (avoid Z-fighting).
        this.needsQuadDedup = isTransparentMaterial(materialKey);
        this.quadKeys = needsQuadDedup ? new HashSet<>() : null;
        
        // Initialize reusable buffer
        this.quadBuffer = new TempVertex[4];
        for (int i = 0; i < 4; i++) this.quadBuffer[i] = new TempVertex();
    }

    // Process one quad and deduplicate vertices.
    void processQuad(BufferedSceneSink.QuadRecord quad) {
        int[] order = sortQuadCCW(quad.positions());
        int spriteHash = Objects.hash(quad.spriteKey(), quad.overlaySpriteKey());

        int[] verts = new int[4];
        
        // Pass 1: Quantize and Lookup (Zero Allocation)
        for (int i = 0; i < 4; i++) {
            int oi = order[i];
            float[] pos = quad.positions();
            float[] uv = quad.uv0();
            float[] uv1Array = quad.uv1();
            float[] col = quad.colors();

            // Populate reusable probe
            TempVertex probe = quadBuffer[i];
            probe.set(
                spriteHash,
                pos[oi * 3], pos[oi * 3 + 1], pos[oi * 3 + 2],
                uv[oi * 2], uv[oi * 2 + 1],
                uv1Array != null ? uv1Array[oi * 2] : 0f,
                uv1Array != null ? uv1Array[oi * 2 + 1] : 0f,
                col[oi * 4], col[oi * 4 + 1], col[oi * 4 + 2], col[oi * 4 + 3]
            );

            int existing = vertexLookup.get(probe);
            verts[i] = existing;
        }

        // Degenerate check
        if ((verts[0] != -1 && (verts[0] == verts[1] || verts[0] == verts[3])) ||
            (verts[1] != -1 && (verts[1] == verts[2])) ||
            (verts[2] != -1 && (verts[2] == verts[3]))) {
             if (verts[0] != -1 && verts[0] == verts[1]) return;
        }
        
        // Pass 2: Register new vertices
        for (int i = 0; i < 4; i++) {
            if (verts[i] == -1) {
                TempVertex temp = quadBuffer[i];
                
                // Create immutable key for map storage (Allocation only on miss)
                VertexKey key = new VertexKey(temp);
                int idx = vertexCount++;
                vertexLookup.put(key, idx);
                verts[i] = idx;

                // Store vertex data
                positions.add(temp.px); positions.add(temp.py); positions.add(temp.pz);
                uv0.add(temp.u); uv0.add(temp.v);
                uv1.add(temp.u1); uv1.add(temp.v1);
                colors.add(temp.r); colors.add(temp.g); colors.add(temp.b); colors.add(temp.a);
            }
        }

        // Quad-level dedup check (Post-resolution)
        if (needsQuadDedup) {
            QuadKey qk = QuadKey.from(verts[0], verts[1], verts[2], verts[3]);
            if (!quadKeys.add(qk)) {
                return; // Duplicate quad, skip.
            }
        }

        quads.add(new DeduplicatedQuad(
            quad.spriteKey(),
            quad.overlaySpriteKey(),
            verts,
            quad.normal(),
            quad.doubleSided()
        ));
    }

    // Flush deduplicated data to the target sink.
    void flushTo(SceneSink target) {
        if (quads.isEmpty()) {
            releaseMemory();
            return;
        }

        try {
            // OPTIMIZATION: Batch transfer for GltfSceneBuilder to reduce lock contention
            if (target instanceof com.voxelbridge.export.scene.gltf.GltfSceneBuilder gltfSink) {
                int count = quads.size();
                List<String> spriteKeys = new ArrayList<>(count);
                List<String> overlaySpriteKeys = new ArrayList<>(count);
                
                // Allocate flat arrays (Single allocation per batch instead of N per quad)
                float[] flatPositions = new float[count * 12];
                float[] flatUv0s = new float[count * 8];
                float[] flatUv1s = new float[count * 8];
                float[] flatNormals = new float[count * 3];
                float[] flatColors = new float[count * 16];
                List<Boolean> allDoubleSided = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    DeduplicatedQuad quad = quads.get(i);
                    int posBase = i * 12;
                    int uvBase = i * 8;
                    int normBase = i * 3;
                    int colBase = i * 16;

                    for (int v = 0; v < 4; v++) {
                        int vertIdx = quad.vertexIndices()[v];

                        // positions
                        flatPositions[posBase + v * 3]     = positions.get(vertIdx * 3);
                        flatPositions[posBase + v * 3 + 1] = positions.get(vertIdx * 3 + 1);
                        flatPositions[posBase + v * 3 + 2] = positions.get(vertIdx * 3 + 2);

                        // uv0
                        flatUv0s[uvBase + v * 2]     = uv0.get(vertIdx * 2);
                        flatUv0s[uvBase + v * 2 + 1] = uv0.get(vertIdx * 2 + 1);

                        // uv1
                        flatUv1s[uvBase + v * 2]     = uv1.get(vertIdx * 2);
                        flatUv1s[uvBase + v * 2 + 1] = uv1.get(vertIdx * 2 + 1);

                        // colors
                        flatColors[colBase + v * 4]     = colors.get(vertIdx * 4);
                        flatColors[colBase + v * 4 + 1] = colors.get(vertIdx * 4 + 1);
                        flatColors[colBase + v * 4 + 2] = colors.get(vertIdx * 4 + 2);
                        flatColors[colBase + v * 4 + 3] = colors.get(vertIdx * 4 + 3);
                    }

                    float[] norm = quad.normal();
                    if (norm != null && norm.length >= 3) {
                        flatNormals[normBase]     = norm[0];
                        flatNormals[normBase + 1] = norm[1];
                        flatNormals[normBase + 2] = norm[2];
                    } else {
                        flatNormals[normBase]     = 0f;
                        flatNormals[normBase + 1] = 1f;
                        flatNormals[normBase + 2] = 0f;
                    }

                    spriteKeys.add(quad.spriteKey());
                    overlaySpriteKeys.add(quad.overlaySpriteKey());
                    allDoubleSided.add(quad.doubleSided());
                }

                gltfSink.addBatch(
                    materialKey,
                    spriteKeys,
                    overlaySpriteKeys,
                    flatPositions,
                    flatUv0s,
                    flatUv1s,
                    flatNormals,
                    flatColors,
                    allDoubleSided
                );
                return;
            }

            for (DeduplicatedQuad quad : quads) {
                float[] quadPositions = new float[12];
                float[] quadUv0 = new float[8];
                float[] quadUv1 = new float[8];
                float[] quadColors = new float[16];

                for (int i = 0; i < 4; i++) {
                    int vertIdx = quad.vertexIndices()[i];

                    quadPositions[i * 3] = positions.get(vertIdx * 3);
                    quadPositions[i * 3 + 1] = positions.get(vertIdx * 3 + 1);
                    quadPositions[i * 3 + 2] = positions.get(vertIdx * 3 + 2);

                    quadUv0[i * 2] = uv0.get(vertIdx * 2);
                    quadUv0[i * 2 + 1] = uv0.get(vertIdx * 2 + 1);

                    quadUv1[i * 2] = uv1.get(vertIdx * 2);
                    quadUv1[i * 2 + 1] = uv1.get(vertIdx * 2 + 1);

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
        } finally {
            releaseMemory();
        }
    }

    int getVertexCount() {
        return vertexCount;
    }

    int getQuadCount() {
        return quads.size();
    }

    // ==================== Helper methods ====================

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

    // Mutable holder for vertex data + key logic
    // Extends VertexKey so it can be used as a query key for TObjectIntHashMap
    private class TempVertex extends VertexKey {
        float px, py, pz, u, v, u1, v1, r, g, b, a;

        void set(int spriteHash,
                 float px, float py, float pz,
                 float u, float v,
                 float u1, float v1,
                 float r, float g, float b, float a) {
            this.px = px; this.py = py; this.pz = pz;
            this.u = u; this.v = v;
            this.u1 = u1; this.v1 = v1;
            this.r = r; this.g = g; this.b = b; this.a = a;
            
            // Update key fields
            this.spriteHash = spriteHash;
            this.k_px = quantize(px); this.k_py = quantize(py); this.k_pz = quantize(pz);
            this.k_u = quantizeUV(u); this.k_v = quantizeUV(v);
            this.k_u1 = quantizeUV(u1); this.k_v1 = quantizeUV(v1);
            this.k_r = quantizeColor(r); this.k_g = quantizeColor(g);
            this.k_b = quantizeColor(b); this.k_a = quantizeColor(a);
        }
    }

    // Base class for hashing logic.
    // Used as the immutable key in the map.
    private static class VertexKey {
        int spriteHash;
        int k_px, k_py, k_pz;
        int k_u, k_v, k_u1, k_v1;
        int k_r, k_g, k_b, k_a;

        VertexKey() {}

        VertexKey(TempVertex other) {
            this.spriteHash = other.spriteHash;
            this.k_px = other.k_px; this.k_py = other.k_py; this.k_pz = other.k_pz;
            this.k_u = other.k_u; this.k_v = other.k_v;
            this.k_u1 = other.k_u1; this.k_v1 = other.k_v1;
            this.k_r = other.k_r; this.k_g = other.k_g;
            this.k_b = other.k_b; this.k_a = other.k_a;
        }

        @Override
        public int hashCode() {
            int result = spriteHash;
            result = 31 * result + k_px;
            result = 31 * result + k_py;
            result = 31 * result + k_pz;
            result = 31 * result + k_u;
            result = 31 * result + k_v;
            result = 31 * result + k_u1;
            result = 31 * result + k_v1;
            result = 31 * result + k_r;
            result = 31 * result + k_g;
            result = 31 * result + k_b;
            result = 31 * result + k_a;
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VertexKey other)) return false;
            return spriteHash == other.spriteHash &&
                k_px == other.k_px && k_py == other.k_py && k_pz == other.k_pz &&
                k_u == other.k_u && k_v == other.k_v &&
                k_u1 == other.k_u1 && k_v1 == other.k_v1 &&
                k_r == other.k_r && k_g == other.k_g &&
                k_b == other.k_b && k_a == other.k_a;
        }
    }

    // Quad key for quad-level dedup.
    private record QuadKey(int a, int b, int c, int d) {
        static QuadKey from(int v0, int v1, int v2, int v3) {
            int[] arr = new int[]{v0, v1, v2, v3};
            Arrays.sort(arr);
            return new QuadKey(arr[0], arr[1], arr[2], arr[3]);
        }
    }

    // Sort quad vertices in CCW order.
    private int[] sortQuadCCW(float[] pos) {
        Integer[] idx = {0, 1, 2, 3};

        float ax = pos[3] - pos[0], ay = pos[4] - pos[1], az = pos[5] - pos[2];
        float bx = pos[6] - pos[0], by = pos[7] - pos[1], bz = pos[8] - pos[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);

        int drop = (anx >= any && anx >= anz) ? 0 : (any >= anz ? 1 : 2);

        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += pos[i * 3];
            cy += pos[i * 3 + 1];
            cz += pos[i * 3 + 2];
        }
        final float fcx = cx * 0.25f, fcy = cy * 0.25f, fcz = cz * 0.25f;
        final int fdrop = drop;

        Arrays.sort(idx, (i1, i2) -> {
            float x1 = pos[i1 * 3] - fcx, y1 = pos[i1 * 3 + 1] - fcy, z1 = pos[i1 * 3 + 2] - fcz;
            float x2 = pos[i2 * 3] - fcx, y2 = pos[i2 * 3 + 1] - fcy, z2 = pos[i2 * 3 + 2] - fcz;
            double a1 = (fdrop == 0) ? Math.atan2(z1, y1) : (fdrop == 1) ? Math.atan2(z1, x1) : Math.atan2(y1, x1);
            double a2 = (fdrop == 0) ? Math.atan2(z2, y2) : (fdrop == 1) ? Math.atan2(z2, x2) : Math.atan2(y2, x2);
            return Double.compare(a1, a2);
        });

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

    // Release internal buffers to avoid retaining transparent culling data across LOD batches.
    private void releaseMemory() {
        clearAndTrim(positions);
        clearAndTrim(uv0);
        clearAndTrim(uv1);
        clearAndTrim(colors);
        quads.clear();
        if (needsQuadDedup && quadKeys != null) {
            quadKeys.clear();
        }
        vertexLookup.clear();
        vertexCount = 0;
    }

    private static <T> void clearAndTrim(List<T> list) {
        list.clear();
        if (list instanceof ArrayList<?> arrayList) {
            arrayList.trimToSize();
        }
    }
}
