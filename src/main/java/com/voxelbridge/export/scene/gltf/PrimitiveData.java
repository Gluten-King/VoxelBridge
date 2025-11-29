package com.voxelbridge.export.scene.gltf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Accumulates vertices/indices for a single material group (e.g. "minecraft:glass").
 * Tracks sprite usage ranges for atlas UV remapping.
 */
final class PrimitiveData {
    final String materialGroupKey;
    final FloatList positions = new FloatList();
    final FloatList uv0 = new FloatList();
    final FloatList uv1 = new FloatList();
    final FloatList colors = new FloatList();
    final IntList indices = new IntList();
    
    // To properly merge vertices within the same material group, we need to distinguish
    // vertices coming from different sprites (as they will have different remapped UVs).
    // The VertexKey now includes a hash of the spriteKey.
    final Map<VertexKey, Integer> vertexLookup = new HashMap<>();
    final Set<QuadKey> quadKeys = new HashSet<>();
    int vertexCount = 0;
    boolean doubleSided = false;
    
    // Track which vertices use which sprite (for atlas remapping)
    // Range is [startVertexIndex, count] in terms of the FLAT arrays (not indices buffer)
    // Actually, remapping iterates per vertex.
    record SpriteRange(int startVertexIndex, int count, String spriteKey, String overlaySpriteKey) {}
    final List<SpriteRange> spriteRanges = new ArrayList<>();

    PrimitiveData(String materialGroupKey) {
        this.materialGroupKey = materialGroupKey;
    }

    /**
     * Registers a quad. Returns the indices [v0, v1, v2, v3] or null if degenerate/duplicate.
     */
    int[] registerQuad(String spriteKey, String overlaySpriteKey, float[] pos, float[] uv, float[] uv1In, float[] col) {
        int startVert = vertexCount;

        // Reorder quad vertices into a consistent CCW order
        int[] order = sortQuadCCW(pos);

        // Use a hash of the spriteKey to prevent merging vertices from different source textures
        // (because they will need different Atlas UV remapping later).
        int spriteHash = java.util.Objects.hash(spriteKey, overlaySpriteKey);

        int v0 = registerVertex(
                spriteHash,
                pos[order[0] * 3], pos[order[0] * 3 + 1], pos[order[0] * 3 + 2],
                uv[order[0] * 2], uv[order[0] * 2 + 1],
                uv1In != null ? uv1In[order[0] * 2] : 0f,
                uv1In != null ? uv1In[order[0] * 2 + 1] : 0f,
                col[order[0] * 4], col[order[0] * 4 + 1], col[order[0] * 4 + 2], col[order[0] * 4 + 3]);
        int v1 = registerVertex(
                spriteHash,
                pos[order[1] * 3], pos[order[1] * 3 + 1], pos[order[1] * 3 + 2],
                uv[order[1] * 2], uv[order[1] * 2 + 1],
                uv1In != null ? uv1In[order[1] * 2] : 0f,
                uv1In != null ? uv1In[order[1] * 2 + 1] : 0f,
                col[order[1] * 4], col[order[1] * 4 + 1], col[order[1] * 4 + 2], col[order[1] * 4 + 3]);
        int v2 = registerVertex(
                spriteHash,
                pos[order[2] * 3], pos[order[2] * 3 + 1], pos[order[2] * 3 + 2],
                uv[order[2] * 2], uv[order[2] * 2 + 1],
                uv1In != null ? uv1In[order[2] * 2] : 0f,
                uv1In != null ? uv1In[order[2] * 2 + 1] : 0f,
                col[order[2] * 4], col[order[2] * 4 + 1], col[order[2] * 4 + 2], col[order[2] * 4 + 3]);
        int v3 = registerVertex(
                spriteHash,
                pos[order[3] * 3], pos[order[3] * 3 + 1], pos[order[3] * 3 + 2],
                uv[order[3] * 2], uv[order[3] * 2 + 1],
                uv1In != null ? uv1In[order[3] * 2] : 0f,
                uv1In != null ? uv1In[order[3] * 2 + 1] : 0f,
                col[order[3] * 4], col[order[3] * 4 + 1], col[order[3] * 4 + 2], col[order[3] * 4 + 3]);

        if (v0 == v1 || v1 == v2 || v2 == v3 || v0 == v3) {
            return null;
        }
        QuadKey qk = QuadKey.from(v0, v1, v2, v3);
        if (!quadKeys.add(qk)) {
            return null;
        }

        // Record the range of vertices added/reused for this quad.
        // We track the logical vertices added to the primitive.
        // Since registerVertex might reuse existing vertices, strictly speaking "range" logic
        // for UV remapping is tricky if vertices are reused across different logical quads.
        // BUT, we added spriteHash to VertexKey. So vertices from different sprites WILL NOT merge.
        // Therefore, all 4 vertices (v0..v3) returned here definitely belong to `spriteKey`.
        // We can just append a range of length 4, or optimize if they are sequential.
        // Actually, the vertices in `positions/uv0` list are sequential.
        // Wait, `registerVertex` reuses indices, but the data in `positions` is only added if NEW.
        // UV Remapping needs to iterate over the `uv0` array.
        // So we need to track which *indices in the uv0 array* correspond to which sprite.
        // `vertexCount` tracks the size of these arrays.
        // If we reused a vertex, no new data was added to `uv0`.
        // That reused vertex already belongs to a range we recorded earlier (because spriteHash matched).
        // So we only need to record a range if we *added new vertices*.
        int addedCount = vertexCount - startVert;
        if (addedCount > 0) {
            if (!spriteRanges.isEmpty()) {
                SpriteRange last = spriteRanges.get(spriteRanges.size() - 1);
                if (last.spriteKey.equals(spriteKey) && 
                   (overlaySpriteKey == null ? last.overlaySpriteKey == null : overlaySpriteKey.equals(last.overlaySpriteKey)) &&
                   last.startVertexIndex + last.count == startVert) {
                    // Extend previous range
                    spriteRanges.set(spriteRanges.size() - 1, new SpriteRange(last.startVertexIndex, last.count + addedCount, spriteKey, overlaySpriteKey));
                } else {
                    spriteRanges.add(new SpriteRange(startVert, addedCount, spriteKey, overlaySpriteKey));
                }
            } else {
                spriteRanges.add(new SpriteRange(startVert, addedCount, spriteKey, overlaySpriteKey));
            }
        }
        
        return new int[]{v0, v1, v2, v3};
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
                
        Integer existing = vertexLookup.get(key);
        if (existing != null) {
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

    private int quantize(float v) { return Math.round(v * 10000f); }
    private int quantizeUV(float v) { return Math.round(v * 100000f); }
    private int quantizeColor(float v) { return Math.round(v * 100f); }

    private record VertexKey(int spriteHash, int px, int py, int pz, int u, int v, int u1, int v1,
                             int r, int g, int b, int a) {}

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
