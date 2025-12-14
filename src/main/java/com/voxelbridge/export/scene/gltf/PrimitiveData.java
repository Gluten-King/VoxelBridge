package com.voxelbridge.export.scene.gltf;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/**
 * Accumulates vertices/indices for a single material group (e.g. "minecraft:glass").
 * Tracks sprite usage ranges for atlas UV remapping.
 */
final class PrimitiveData {
    final String materialGroupKey;
    final FloatList positions;
    final FloatList uv0;
    final FloatList uv1;
    final FloatList colors;
    final IntList indices;
    
    // To properly merge vertices within the same material group, we need to distinguish
    // vertices coming from different sprites (as they will have different remapped UVs).
    // The VertexKey now includes a hash of the spriteKey.
    // Using TObjectIntHashMap to reduce memory overhead compared to HashMap<VertexKey, Integer>
    final TObjectIntHashMap<VertexKey> vertexLookup = new TObjectIntHashMap<>(10, 0.5f, -1);
    // OPTIMIZATION: QuadKey dedup only for transparent materials (saves ~600MB for opaque materials)
    final Set<QuadKey> quadKeys;
    final boolean needsQuadDedup;
    int vertexCount = 0;
    boolean doubleSided = false;
    
    // Track which vertices use which sprite (for atlas remapping)
    // Range is [startVertexIndex, count] in terms of the FLAT arrays (not indices buffer)
    // Actually, remapping iterates per vertex.
    record SpriteRange(int startVertexIndex, int count, String spriteKey, String overlaySpriteKey) {}
    final List<SpriteRange> spriteRanges = new ArrayList<>();

    PrimitiveData(String materialGroupKey) {
        this(materialGroupKey, 0);
    }

    PrimitiveData(String materialGroupKey, int estimatedQuads) {
        this.materialGroupKey = materialGroupKey;
        int vertexCapacity = (estimatedQuads > 0) ? estimatedQuads * 4 : 256;
        int indexCapacity = (estimatedQuads > 0) ? estimatedQuads * 6 : 256;
        this.positions = new FloatList(vertexCapacity * 3);
        this.uv0 = new FloatList(vertexCapacity * 2);
        this.uv1 = new FloatList(vertexCapacity * 2);
        this.colors = new FloatList(vertexCapacity * 4);
        this.indices = new IntList(indexCapacity);
        // Quad dedup is only needed for transparent materials to prevent Z-fighting
        this.needsQuadDedup = isTransparentMaterial(materialGroupKey);
        this.quadKeys = needsQuadDedup ? new HashSet<>() : null;
    }

    private static boolean isTransparentMaterial(String materialKey) {
        if (materialKey == null) return false;
        String lower = materialKey.toLowerCase();
        // Common transparent materials that benefit from quad deduplication
        return lower.contains("glass") || lower.contains("leaves") ||
               lower.contains("water") || lower.contains("ice") ||
               lower.contains("slime") || lower.contains("honey") ||
               lower.contains("portal") || lower.contains("stained_glass");
    }

    /**
     * Registers a quad. Returns the indices [v0, v1, v2, v3] or null if degenerate/duplicate.
     */
    int[] registerQuad(String spriteKey, String overlaySpriteKey, float[] pos, float[] uv, float[] uv1In, float[] col) {
        int startVert = vertexCount;
        int[] order = sortQuadCCW(pos);
        int spriteHash = Objects.hash(spriteKey, overlaySpriteKey);

        // Stage vertices first to avoid partially writing data for degenerate/duplicate quads.
        List<PendingVertex> pending = new ArrayList<>(4);
        int[] verts = new int[4];
        for (int i = 0; i < 4; i++) {
            int oi = order[i];
            PendingVertex pv = new PendingVertex(
                    new VertexKey(
                            spriteHash,
                            quantize(pos[oi * 3]), quantize(pos[oi * 3 + 1]), quantize(pos[oi * 3 + 2]),
                            quantizeUV(uv[oi * 2]), quantizeUV(uv[oi * 2 + 1]),
                            quantizeUV(uv1In != null ? uv1In[oi * 2] : 0f), quantizeUV(uv1In != null ? uv1In[oi * 2 + 1] : 0f),
                            quantizeColor(col[oi * 4]), quantizeColor(col[oi * 4 + 1]), quantizeColor(col[oi * 4 + 2]), quantizeColor(col[oi * 4 + 3])
                    ),
                    pos[oi * 3], pos[oi * 3 + 1], pos[oi * 3 + 2],
                    uv[oi * 2], uv[oi * 2 + 1],
                    uv1In != null ? uv1In[oi * 2] : 0f, uv1In != null ? uv1In[oi * 2 + 1] : 0f,
                    col[oi * 4], col[oi * 4 + 1], col[oi * 4 + 2], col[oi * 4 + 3]
            );

            int existing = vertexLookup.get(pv.key());
            if (existing != -1) {
                verts[i] = existing;
            } else {
                verts[i] = vertexCount + pending.size();
                pending.add(pv);
            }
        }

        if (verts[0] == verts[1] || verts[1] == verts[2] || verts[2] == verts[3] || verts[0] == verts[3]) {
            return null;
        }
        // OPTIMIZATION: Only perform quad dedup for transparent materials
        if (needsQuadDedup) {
            QuadKey qk = QuadKey.from(verts[0], verts[1], verts[2], verts[3]);
            if (!quadKeys.add(qk)) {
                return null;
            }
        }

        // Commit staged vertices
        for (PendingVertex pv : pending) {
            int idx = vertexCount++;
            vertexLookup.put(pv.key(), idx);
            positions.add(pv.px()); positions.add(pv.py()); positions.add(pv.pz());
            uv0.add(pv.u()); uv0.add(pv.v());
            uv1.add(pv.u1()); uv1.add(pv.v1());
            colors.add(pv.r()); colors.add(pv.g()); colors.add(pv.b()); colors.add(pv.a());
        }

        int addedCount = pending.size();
        if (addedCount > 0) {
            if (!spriteRanges.isEmpty()) {
                SpriteRange last = spriteRanges.get(spriteRanges.size() - 1);
                if (last.spriteKey.equals(spriteKey) &&
                   (overlaySpriteKey == null ? last.overlaySpriteKey == null : overlaySpriteKey.equals(last.overlaySpriteKey)) &&
                   last.startVertexIndex + last.count == startVert) {
                    spriteRanges.set(spriteRanges.size() - 1, new SpriteRange(last.startVertexIndex, last.count + addedCount, spriteKey, overlaySpriteKey));
                } else {
                    spriteRanges.add(new SpriteRange(startVert, addedCount, spriteKey, overlaySpriteKey));
                }
            } else {
                spriteRanges.add(new SpriteRange(startVert, addedCount, spriteKey, overlaySpriteKey));
            }
        }

        return verts;
    }

    int registerVertex(int spriteHash,
                       float px, float py, float pz,
                       float u, float v,
                       float u1, float v1,
                       float r, float g, float b, float a) {
        VertexKey key = new VertexKey(
                spriteHash, // Differentiate vertices by source texture
                quantize(px), quantize(py), quantize(pz),
                quantizeUV(u), quantizeUV(v),
                quantizeUV(u1), quantizeUV(v1),
                quantizeColor(r), quantizeColor(g), quantizeColor(b), quantizeColor(a));

        int existing = vertexLookup.get(key);
        if (existing != -1) {
            return existing;
        }
        int idx = vertexCount++;
        vertexLookup.put(key, idx);
        positions.add(px); positions.add(py); positions.add(pz);
        uv0.add(u); uv0.add(v);
        uv1.add(u1); uv1.add(v1);
        colors.add(r); colors.add(g); colors.add(b); colors.add(a);
        return idx;
    }

    void addTriangle(int a, int b, int c) {
        indices.add(a);
        indices.add(b);
        indices.add(c);
    }

    float[] positionMin() { return positions.computeMin(); }
    float[] positionMax() { return positions.computeMax(); }
    int maxIndex() {
        int max = 0;
        for (int value : indices.toArray()) max = Math.max(max, value);
        return max;
    }

    /**
     * Release internal data structures to help GC.
     * Call this after the data has been merged/written and is no longer needed.
     */
    void releaseMemory() {
        positions.clear();
        uv0.clear();
        uv1.clear();
        colors.clear();
        indices.clear();
        vertexLookup.clear();
        if (quadKeys != null) {
            quadKeys.clear();
        }
        spriteRanges.clear();
        vertexCount = 0;
        doubleSided = false;
    }

    private int quantize(float v) { return Math.round(v * 10000f); }
    private int quantizeUV(float v) { return Math.round(v * 100000f); }
    private int quantizeColor(float v) { return Math.round(v * 100f); }

    private record VertexKey(int spriteHash, int px, int py, int pz, int u, int v, int u1, int v1,
                             int r, int g, int b, int a) {}

    private record PendingVertex(VertexKey key,
                                 float px, float py, float pz,
                                 float u, float v,
                                 float u1, float v1,
                                 float r, float g, float b, float a) {}

    private record QuadKey(int a, int b, int c, int d) {
        static QuadKey from(int v0, int v1, int v2, int v3) {
            int[] arr = new int[]{v0, v1, v2, v3};
            Arrays.sort(arr);
            return new QuadKey(arr[0], arr[1], arr[2], arr[3]);
        }
    }

    private int[] sortQuadCCW(float[] pos) {
        // Implementation from previous snippets (omitted for brevity, assume strictly kept)
        // Copy logic from provided PrimitiveData.java
        Integer[] idx = {0, 1, 2, 3};
        float ax = pos[3] - pos[0], ay = pos[4] - pos[1], az = pos[5] - pos[2];
        float bx = pos[6] - pos[0], by = pos[7] - pos[1], bz = pos[8] - pos[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
        int drop = (anx >= any && anx >= anz) ? 0 : (any >= anz ? 1 : 2);
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) { cx += pos[i*3]; cy += pos[i*3+1]; cz += pos[i*3+2]; }
        final float fcx = cx*0.25f, fcy = cy*0.25f, fcz = cz*0.25f;
        final int fdrop = drop;
        Arrays.sort(idx, (i1, i2) -> {
            float x1 = pos[i1*3]-fcx, y1 = pos[i1*3+1]-fcy, z1 = pos[i1*3+2]-fcz;
            float x2 = pos[i2*3]-fcx, y2 = pos[i2*3+1]-fcy, z2 = pos[i2*3+2]-fcz;
            double a1 = (fdrop==0)?Math.atan2(z1,y1):(fdrop==1)?Math.atan2(z1,x1):Math.atan2(y1,x1);
            double a2 = (fdrop==0)?Math.atan2(z2,y2):(fdrop==1)?Math.atan2(z2,x2):Math.atan2(y2,x2);
            return Double.compare(a1, a2);
        });
        float ovx1 = pos[3]-pos[0], ovy1 = pos[4]-pos[1], ovz1 = pos[5]-pos[2];
        float ovx2 = pos[6]-pos[0], ovy2 = pos[7]-pos[1], ovz2 = pos[8]-pos[2];
        float onx = ovy1*ovz2-ovz1*ovy2, ony = ovz1*ovx2-ovx1*ovz2, onz = ovx1*ovy2-ovy1*ovx2;
        float svx1 = pos[idx[1]*3]-pos[idx[0]*3], svy1 = pos[idx[1]*3+1]-pos[idx[0]*3+1], svz1 = pos[idx[1]*3+2]-pos[idx[0]*3+2];
        float svx2 = pos[idx[2]*3]-pos[idx[0]*3], svy2 = pos[idx[2]*3+1]-pos[idx[0]*3+1], svz2 = pos[idx[2]*3+2]-pos[idx[0]*3+2];
        float snx = svy1*svz2-svz1*svy2, sny = svz1*svx2-svx1*svz2, snz = svx1*svy2-svy1*svx2;
        if (onx*snx + ony*sny + onz*snz < 0) { int tmp = idx[1]; idx[1] = idx[3]; idx[3] = tmp; }
        return new int[]{idx[0], idx[1], idx[2], idx[3]};
    }
}
