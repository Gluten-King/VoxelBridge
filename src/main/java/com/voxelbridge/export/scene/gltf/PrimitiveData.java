package com.voxelbridge.export.scene.gltf;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates vertices/indices for a single sprite/material.
 */
final class PrimitiveData {
    final String spriteKey;
    final FloatList positions = new FloatList();
    final FloatList uv0 = new FloatList();
    final FloatList uv1 = new FloatList();
    final FloatList uv2 = new FloatList();
    final FloatList uv3 = new FloatList();
    final FloatList colors = new FloatList();
    final IntList indices = new IntList();
    final Map<VertexKey, Integer> vertexLookup = new HashMap<>();
    final Set<QuadKey> quadKeys = new HashSet<>();
    int vertexCount = 0;
    boolean doubleSided = false;
    private boolean hasUv2 = false;
    private boolean hasUv3 = false;
    String overlaySpriteKey = null;  // Overlay texture key for atlas remapping of uv2

    PrimitiveData(String spriteKey) {
        this.spriteKey = spriteKey;
    }

    int[] registerQuad(float[] pos, float[] uv, float[] uv1In, float[] uv2In, float[] uv3In, float[] col) {
        // Reorder quad vertices into a consistent CCW order to avoid butterfly faces
        int[] order = sortQuadCCW(pos);
        boolean quadHasUv2 = uv2In != null;
        boolean quadHasUv3 = uv3In != null;

        int v0 = registerVertex(
                pos[order[0] * 3], pos[order[0] * 3 + 1], pos[order[0] * 3 + 2],
                uv[order[0] * 2], uv[order[0] * 2 + 1],
                uv1In != null ? uv1In[order[0] * 2] : 0f,
                uv1In != null ? uv1In[order[0] * 2 + 1] : 0f,
                quadHasUv2 ? uv2In[order[0] * 2] : 0f,
                quadHasUv2 ? uv2In[order[0] * 2 + 1] : 0f,
                quadHasUv2,
                quadHasUv3 ? uv3In[order[0] * 2] : 0f,
                quadHasUv3 ? uv3In[order[0] * 2 + 1] : 0f,
                quadHasUv3,
                col[order[0] * 4], col[order[0] * 4 + 1], col[order[0] * 4 + 2], col[order[0] * 4 + 3]);
        int v1 = registerVertex(
                pos[order[1] * 3], pos[order[1] * 3 + 1], pos[order[1] * 3 + 2],
                uv[order[1] * 2], uv[order[1] * 2 + 1],
                uv1In != null ? uv1In[order[1] * 2] : 0f,
                uv1In != null ? uv1In[order[1] * 2 + 1] : 0f,
                quadHasUv2 ? uv2In[order[1] * 2] : 0f,
                quadHasUv2 ? uv2In[order[1] * 2 + 1] : 0f,
                quadHasUv2,
                quadHasUv3 ? uv3In[order[1] * 2] : 0f,
                quadHasUv3 ? uv3In[order[1] * 2 + 1] : 0f,
                quadHasUv3,
                col[order[1] * 4], col[order[1] * 4 + 1], col[order[1] * 4 + 2], col[order[1] * 4 + 3]);
        int v2 = registerVertex(
                pos[order[2] * 3], pos[order[2] * 3 + 1], pos[order[2] * 3 + 2],
                uv[order[2] * 2], uv[order[2] * 2 + 1],
                uv1In != null ? uv1In[order[2] * 2] : 0f,
                uv1In != null ? uv1In[order[2] * 2 + 1] : 0f,
                quadHasUv2 ? uv2In[order[2] * 2] : 0f,
                quadHasUv2 ? uv2In[order[2] * 2 + 1] : 0f,
                quadHasUv2,
                quadHasUv3 ? uv3In[order[2] * 2] : 0f,
                quadHasUv3 ? uv3In[order[2] * 2 + 1] : 0f,
                quadHasUv3,
                col[order[2] * 4], col[order[2] * 4 + 1], col[order[2] * 4 + 2], col[order[2] * 4 + 3]);
        int v3 = registerVertex(
                pos[order[3] * 3], pos[order[3] * 3 + 1], pos[order[3] * 3 + 2],
                uv[order[3] * 2], uv[order[3] * 2 + 1],
                uv1In != null ? uv1In[order[3] * 2] : 0f,
                uv1In != null ? uv1In[order[3] * 2 + 1] : 0f,
                quadHasUv2 ? uv2In[order[3] * 2] : 0f,
                quadHasUv2 ? uv2In[order[3] * 2 + 1] : 0f,
                quadHasUv2,
                quadHasUv3 ? uv3In[order[3] * 2] : 0f,
                quadHasUv3 ? uv3In[order[3] * 2 + 1] : 0f,
                quadHasUv3,
                col[order[3] * 4], col[order[3] * 4 + 1], col[order[3] * 4 + 2], col[order[3] * 4 + 3]);

        if (v0 == v1 || v1 == v2 || v2 == v3 || v0 == v3) {
            return null;
        }
        QuadKey qk = QuadKey.from(v0, v1, v2, v3);
        if (!quadKeys.add(qk)) {
            return null;
        }
        return new int[]{v0, v1, v2, v3};
    }

    int registerVertex(float px, float py, float pz,
                       float u, float v,
                       float u1, float v1,
                       float u2, float v2,
                       boolean vertexHasUv2,
                       float u3, float v3,
                       boolean vertexHasUv3,
                       float r, float g, float b, float a) {
        if (vertexHasUv2) {
            hasUv2 = true;
        }
        if (vertexHasUv3) {
            hasUv3 = true;
        }
        int quantizedU2 = vertexHasUv2 ? quantizeUV(u2) : Integer.MIN_VALUE;
        int quantizedV2 = vertexHasUv2 ? quantizeUV(v2) : Integer.MIN_VALUE;
        int quantizedU3 = vertexHasUv3 ? quantizeUV(u3) : Integer.MIN_VALUE;
        int quantizedV3 = vertexHasUv3 ? quantizeUV(v3) : Integer.MIN_VALUE;
        VertexKey key = new VertexKey(
                quantize(px), quantize(py), quantize(pz),
                quantizeUV(u), quantizeUV(v),
                quantizeUV(u1), quantizeUV(v1),
                quantizedU2, quantizedV2,
                vertexHasUv2 ? 1 : 0,
                quantizedU3, quantizedV3,
                vertexHasUv3 ? 1 : 0,
                quantizeColor(r), quantizeColor(g), quantizeColor(b), quantizeColor(a));
        Integer existing = vertexLookup.get(key);
        if (existing != null) {
            return existing;
        }
        int idx = vertexCount++;
        vertexLookup.put(key, idx);
        positions.add(px);
        positions.add(py);
        positions.add(pz);
        uv0.add(u);
        uv0.add(v);
        uv1.add(u1);
        uv1.add(v1);
        uv2.add(u2);
        uv2.add(v2);
        uv3.add(u3);
        uv3.add(v3);
        colors.add(r);
        colors.add(g);
        colors.add(b);
        colors.add(a);
        return idx;
    }

    boolean hasUv2() {
        return hasUv2;
    }

    boolean hasUv3() {
        return hasUv3;
    }

    void addTriangle(int a, int b, int c) {
        indices.add(a);
        indices.add(b);
        indices.add(c);
    }

    float[] positionMin() {
        return positions.computeMin();
    }

    float[] positionMax() {
        return positions.computeMax();
    }

    int maxIndex() {
        int max = 0;
        for (int value : indices.toArray()) {
            max = Math.max(max, value);
        }
        return max;
    }

    double dist2(int viA, int viB) {
        int ia = viA * 3;
        int ib = viB * 3;
        double dx = positions.get(ia) - positions.get(ib);
        double dy = positions.get(ia + 1) - positions.get(ib + 1);
        double dz = positions.get(ia + 2) - positions.get(ib + 2);
        return dx * dx + dy * dy + dz * dz;
    }

    private int quantize(float v) {
        return Math.round(v * 10000f); // weld within 0.0001 units (position)
    }

    private int quantizeUV(float v) {
        return Math.round(v * 100000f); // tighter weld to preserve UVs near 0/1 boundaries
    }

    private int quantizeColor(float v) {
        return Math.round(v * 100f); // weld within 0.01 color space (relaxed for vertex colors)
    }

    private record VertexKey(int px, int py, int pz, int u, int v, int u1, int v1, int u2, int v2,
                             int uv2Flag, int u3, int v3, int uv3Flag, int r, int g, int b, int a) {}

    private record QuadKey(int a, int b, int c, int d) {
        static QuadKey from(int v0, int v1, int v2, int v3) {
            int[] arr = new int[]{v0, v1, v2, v3};
            Arrays.sort(arr);
            return new QuadKey(arr[0], arr[1], arr[2], arr[3]);
        }
    }

    /**
     * Orders the 4 vertices of a quad in a stable CCW order using projection to the dominant axis plane.
     */
    private int[] sortQuadCCW(float[] pos) {
        Integer[] idx = {0, 1, 2, 3};

        // Compute normal
        float ax = pos[3] - pos[0];
        float ay = pos[4] - pos[1];
        float az = pos[5] - pos[2];
        float bx = pos[6] - pos[0];
        float by = pos[7] - pos[1];
        float bz = pos[8] - pos[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        // Dominant axis for projection
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
        int drop; // 0 -> drop X (project to YZ), 1 -> drop Y, 2 -> drop Z
        if (anx >= any && anx >= anz) {
            drop = 0;
        } else if (any >= anz) {
            drop = 1;
        } else {
            drop = 2;
        }

        // Centroid
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += pos[i * 3];
            cy += pos[i * 3 + 1];
            cz += pos[i * 3 + 2];
        }
        cx *= 0.25f; cy *= 0.25f; cz *= 0.25f;

        final float fcx = cx, fcy = cy, fcz = cz;
        final int fdrop = drop;

        // Sort by angle around centroid in projected plane
        Arrays.sort(idx, (i1, i2) -> {
            float x1 = pos[i1 * 3] - fcx;
            float y1 = pos[i1 * 3 + 1] - fcy;
            float z1 = pos[i1 * 3 + 2] - fcz;
            float x2 = pos[i2 * 3] - fcx;
            float y2 = pos[i2 * 3 + 1] - fcy;
            float z2 = pos[i2 * 3 + 2] - fcz;

            double a1, a2;
            if (fdrop == 0) { // project to YZ
                a1 = Math.atan2(z1, y1);
                a2 = Math.atan2(z2, y2);
            } else if (fdrop == 1) { // project to XZ
                a1 = Math.atan2(z1, x1);
                a2 = Math.atan2(z2, x2);
            } else { // project to XY
                a1 = Math.atan2(y1, x1);
                a2 = Math.atan2(y2, x2);
            }
            return Double.compare(a1, a2);
        });

        // Preserve original winding: if sorted normal opposes original, flip winding
        float ovx1 = pos[3] - pos[0];
        float ovy1 = pos[4] - pos[1];
        float ovz1 = pos[5] - pos[2];
        float ovx2 = pos[6] - pos[0];
        float ovy2 = pos[7] - pos[1];
        float ovz2 = pos[8] - pos[2];
        float onx = ovy1 * ovz2 - ovz1 * ovy2;
        float ony = ovz1 * ovx2 - ovx1 * ovz2;
        float onz = ovx1 * ovy2 - ovy1 * ovx2;

        float svx1 = pos[idx[1] * 3] - pos[idx[0] * 3];
        float svy1 = pos[idx[1] * 3 + 1] - pos[idx[0] * 3 + 1];
        float svz1 = pos[idx[1] * 3 + 2] - pos[idx[0] * 3 + 2];
        float svx2 = pos[idx[2] * 3] - pos[idx[0] * 3];
        float svy2 = pos[idx[2] * 3 + 1] - pos[idx[0] * 3 + 1];
        float svz2 = pos[idx[2] * 3 + 2] - pos[idx[0] * 3 + 2];
        float snx = svy1 * svz2 - svz1 * svy2;
        float sny = svz1 * svx2 - svx1 * svz2;
        float snz = svx1 * svy2 - svy1 * svx2;

        float dot = onx * snx + ony * sny + onz * snz;
        if (dot < 0) {
            int tmp = idx[1];
            idx[1] = idx[3];
            idx[3] = tmp;
        }

        // Unbox back to primitive array
        return new int[]{idx[0], idx[1], idx[2], idx[3]};
    }
}
