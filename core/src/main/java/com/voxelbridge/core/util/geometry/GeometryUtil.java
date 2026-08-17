package com.voxelbridge.core.util.geometry;

/**
 * Utility class for common geometry and color calculations used across exporters.
 * Provides methods for computing normals, handling vertex colors, and UV normalization.
 */
public final class GeometryUtil {

    private GeometryUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Computes the face normal from quad vertex positions using cross product.
     *
     * @param positions 12 floats representing 4 vertices (x,y,z each)
     * @return 3 floats representing the normalized normal vector (nx, ny, nz)
     */
    public static float[] computeFaceNormal(float[] positions) {
        // Vector from vertex 0 to vertex 1
        float ax = positions[3] - positions[0];
        float ay = positions[4] - positions[1];
        float az = positions[5] - positions[2];

        // Vector from vertex 0 to vertex 2
        float bx = positions[6] - positions[0];
        float by = positions[7] - positions[1];
        float bz = positions[8] - positions[2];

        // Cross product: a x b
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        // Normalize
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) {
            return new float[]{0, 1, 0}; // Default to up vector
        }

        return new float[]{nx / len, ny / len, nz / len};
    }

    /**
     * Returns white color for all 4 vertices (1.0 for all RGBA components).
     *
     * @return 16 floats representing white color for 4 vertices
     */
    public static float[] whiteColor() {
        return new float[]{
            1f, 1f, 1f, 1f,  // vertex 0
            1f, 1f, 1f, 1f,  // vertex 1
            1f, 1f, 1f, 1f,  // vertex 2
            1f, 1f, 1f, 1f   // vertex 3
        };
    }

    /**
     * Computes the area scale for a quad using the first 3 vertices.
     * This matches the cross-product magnitude of the first triangle.
     */
    public static float computeQuadArea(float[] positions) {
        if (positions == null || positions.length < 9) {
            return 0f;
        }
        float ax = positions[3] - positions[0];
        float ay = positions[4] - positions[1];
        float az = positions[5] - positions[2];

        float bx = positions[6] - positions[0];
        float by = positions[7] - positions[1];
        float bz = positions[8] - positions[2];

        float cx = ay * bz - az * by;
        float cy = az * bx - ax * bz;
        float cz = ax * by - ay * bx;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static float srgbToLinearComponent(int c) {
        float v = c / 255.0f;
        if (v <= 0.04045f) {
            return v / 12.92f;
        }
        return (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    /**
     * Computes vertex colors from ARGB tint color.
     *
     * @param argb ARGB color in 0xAARRGGBB format
     * @param hasTint whether the quad has tint enabled
     * @return 16 floats representing RGBA for 4 vertices (same color for all), linear space
     */
    public static float[] computeVertexColors(int argb, boolean hasTint) {
        if (!hasTint || argb == 0xFFFFFFFF || argb == -1) {
            return whiteColor(); // No tint or white tint
        }

        // Extract RGBA and convert sRGB -> linear. Preserve alpha for glTF BLEND materials.
        float r = srgbToLinearComponent((argb >> 16) & 0xFF);
        float g = srgbToLinearComponent((argb >> 8) & 0xFF);
        float b = srgbToLinearComponent(argb & 0xFF);
        int ai = (argb >> 24) & 0xFF;
        // A zero alpha byte with non-zero RGB commonly means no alpha channel was written.
        float a = (ai == 0 && (argb & 0x00FFFFFF) != 0) ? 1.0f : (ai / 255.0f);

        // All 4 vertices use the same color
        return new float[]{
            r, g, b, a,
            r, g, b, a,
            r, g, b, a,
            r, g, b, a
        };
    }

    /**
     * Clamps a value to the [0, 1] range.
     *
     * @param v value to clamp
     * @return clamped value
     */
    public static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * Normalizes UV coordinates from atlas space to sprite-local [0,1] space.
     *
     * @param input 8 floats representing atlas UVs for 4 vertices
     * @param sprite the texture atlas sprite
     * @return 8 floats representing normalized UVs in [0,1] range
     */
    public static float[] normalizeUVs(float[] input, float u0, float u1, float v0, float v1) {
        float[] out = new float[8];
        float du = u1 - u0;
        if (du == 0) du = 1f;
        float dv = v1 - v0;
        if (dv == 0) dv = 1f;

        for (int i = 0; i < 4; i++) {
            float u = input[i * 2];
            float v = input[i * 2 + 1];
            float su = (u - u0) / du;
            float sv = (v - v0) / dv;
            out[i * 2] = clamp01(su);
            out[i * 2 + 1] = clamp01(sv);
        }
        return out;
    }

    /**
     * Adjusts v1 to only cover the first animation frame when a sprite is stacked vertically.
     */
    public static float computeAnimatedV1(float v0, float v1, int spriteWidth, int spriteHeight) {
        if (spriteWidth > 0 && spriteHeight > spriteWidth) {
            int frameCount = spriteHeight / spriteWidth;
            if (frameCount > 0) {
                float frameRatio = 1.0f / frameCount;
                return v0 + (v1 - v0) * frameRatio;
            }
        }
        return v1;
    }

    /**
     * Computes a safe UV span for normalization (avoids divide-by-zero).
     */
    public static float computeUvDelta(float min, float max) {
        float delta = max - min;
        return delta == 0f ? 1f : delta;
    }

    /**
     * Normalizes a single UV pair into sprite-local space.
     */
    public static void normalizeUv(float u, float v, float u0, float v0, float du, float dv,
                                   float[] out, int outIndex) {
        if (out == null || out.length < outIndex + 2) {
            return;
        }
        out[outIndex] = (u - u0) / du;
        out[outIndex + 1] = (v - v0) / dv;
    }

    /**
     * Computes UV bounds for a quad UV array (8 floats).
     * Output layout: [minU, maxU, minV, maxV].
     */
    public static boolean computeUvBounds(float[] uv, float[] out) {
        if (uv == null || uv.length < 8 || out == null || out.length < 4) {
            return false;
        }
        float minU = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
        float maxU = Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]));
        float minV = Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]));
        float maxV = Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7]));
        out[0] = minU;
        out[1] = maxU;
        out[2] = minV;
        out[3] = maxV;
        return true;
    }

    /**
     * Computes the vanilla bush seed (used for blocks like lily pads).
     */
    public static long computeBushSeed(int x, int y, int z) {
        long seed = x * 3129871L ^ z * 116129781L ^ y;
        return seed * seed * 42317861L + seed * 11L;
    }

    /**
     * Computes a stable hash for a quad based on its vertex positions.
     * Vertices are sorted to make the hash order-independent.
     */
    public static long computePositionHash(float[] positions) {
        return computePositionHash(positions, 100f);
    }

    public static long computePositionHash(float[] positions, float quant) {
        if (positions == null || positions.length < 12) {
            return 0L;
        }
        int i0 = 0, i1 = 1, i2 = 2, i3 = 3;

        if (compareVerts(positions, i0, i1) > 0) { int t = i0; i0 = i1; i1 = t; }
        if (compareVerts(positions, i2, i3) > 0) { int t = i2; i2 = i3; i3 = t; }
        if (compareVerts(positions, i0, i2) > 0) { int t = i0; i0 = i2; i2 = t; }
        if (compareVerts(positions, i1, i3) > 0) { int t = i1; i1 = i3; i3 = t; }
        if (compareVerts(positions, i1, i2) > 0) { int t = i1; i1 = i2; i2 = t; }

        long hash = 1125899906842597L;
        hash = hashVertex(hash, positions, i0, quant);
        hash = hashVertex(hash, positions, i1, quant);
        hash = hashVertex(hash, positions, i2, quant);
        hash = hashVertex(hash, positions, i3, quant);
        return hash;
    }

    /**
     * Checks if a quad is an axis-aligned ~1x1 square (within a tolerance).
     */
    public static boolean isApproxAxisAlignedSquare(float[] positions, float sizeTolerance) {
        if (positions == null || positions.length < 12) {
            return false;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float x = positions[i * 3];
            float y = positions[i * 3 + 1];
            float z = positions[i * 3 + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;

        if (sizeX < sizeTolerance) {
            return Math.abs(sizeY - 1f) <= sizeTolerance && Math.abs(sizeZ - 1f) <= sizeTolerance;
        }
        if (sizeY < sizeTolerance) {
            return Math.abs(sizeX - 1f) <= sizeTolerance && Math.abs(sizeZ - 1f) <= sizeTolerance;
        }
        if (sizeZ < sizeTolerance) {
            return Math.abs(sizeX - 1f) <= sizeTolerance && Math.abs(sizeY - 1f) <= sizeTolerance;
        }
        return false;
    }

    /**
     * Returns the dominant axis with sign encoded as:
     * +X=1, -X=-1, +Y=2, -Y=-2, +Z=3, -Z=-3, 0=unknown.
     */
    public static int dominantAxisSigned(float[] normal) {
        if (normal == null || normal.length < 3) {
            return 0;
        }
        float nx = normal[0];
        float ny = normal[1];
        float nz = normal[2];
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        if (ax >= ay && ax >= az) {
            return nx >= 0f ? 1 : -1;
        }
        if (ay >= ax && ay >= az) {
            return ny >= 0f ? 2 : -2;
        }
        return nz >= 0f ? 3 : -3;
    }

    /**
     * Converts local positions to world positions with offsets.
     */
    public static float[] localToWorld(
        float[] localPos,
        float baseX,
        float baseY,
        float baseZ,
        double offsetX,
        double offsetY,
        double offsetZ,
        float randX,
        float randY,
        float randZ
    ) {
        float[] worldPos = new float[12];
        localToWorld(localPos, baseX, baseY, baseZ, offsetX, offsetY, offsetZ, randX, randY, randZ, worldPos);
        return worldPos;
    }

    public static void localToWorld(
        float[] localPos,
        float baseX,
        float baseY,
        float baseZ,
        double offsetX,
        double offsetY,
        double offsetZ,
        float randX,
        float randY,
        float randZ,
        float[] out
    ) {
        if (localPos == null || localPos.length < 12 || out == null || out.length < 12) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            double worldX = baseX + localPos[i * 3] + offsetX + randX;
            double worldY = baseY + localPos[i * 3 + 1] + offsetY + randY;
            double worldZ = baseZ + localPos[i * 3 + 2] + offsetZ + randZ;

            out[i * 3] = (float) worldX;
            out[i * 3 + 1] = (float) worldY;
            out[i * 3 + 2] = (float) worldZ;
        }
    }

    private static int compareVerts(float[] positions, int idxA, int idxB) {
        int ia = idxA * 3;
        int ib = idxB * 3;
        int cmpX = Float.compare(positions[ia], positions[ib]);
        if (cmpX != 0) return cmpX;
        int cmpY = Float.compare(positions[ia + 1], positions[ib + 1]);
        if (cmpY != 0) return cmpY;
        return Float.compare(positions[ia + 2], positions[ib + 2]);
    }

    private static long hashVertex(long hash, float[] positions, int idx, float quant) {
        int pi = idx * 3;
        hash = 31 * hash + Math.round(positions[pi] * quant);
        hash = 31 * hash + Math.round(positions[pi + 1] * quant);
        hash = 31 * hash + Math.round(positions[pi + 2] * quant);
        return hash;
    }

    /**
     * Computes a stable key for quad deduplication with optional UV hashing.
     */
    public static long computeQuadKey(int spriteHash, float[] positions, float[] normal,
                                      boolean doubleSided, float[] uv0) {
        if (positions == null || positions.length < 12) {
            return 0L;
        }
        int i0 = 0, i1 = 1, i2 = 2, i3 = 3;

        if (compareVerts(positions, i0, i1) > 0) { int t = i0; i0 = i1; i1 = t; }
        if (compareVerts(positions, i2, i3) > 0) { int t = i2; i2 = i3; i3 = t; }
        if (compareVerts(positions, i0, i2) > 0) { int t = i0; i0 = i2; i2 = t; }
        if (compareVerts(positions, i1, i3) > 0) { int t = i1; i1 = i3; i3 = t; }
        if (compareVerts(positions, i1, i2) > 0) { int t = i1; i1 = i2; i2 = t; }

        long hash = 1125899906842597L;
        hash = 31 * hash + spriteHash;

        if (!doubleSided && normal != null && normal.length >= 3) {
            hash = 31 * hash + Math.round(normal[0] * 1000f);
            hash = 31 * hash + Math.round(normal[1] * 1000f);
            hash = 31 * hash + Math.round(normal[2] * 1000f);
        }

        hash = hashVertex(hash, positions, uv0, i0);
        hash = hashVertex(hash, positions, uv0, i1);
        hash = hashVertex(hash, positions, uv0, i2);
        hash = hashVertex(hash, positions, uv0, i3);
        return hash;
    }

    private static long hashVertex(long hash, float[] positions, float[] uv0, int idx) {
        int pi = idx * 3;
        hash = 31 * hash + Math.round(positions[pi] * 1000f);
        hash = 31 * hash + Math.round(positions[pi + 1] * 1000f);
        hash = 31 * hash + Math.round(positions[pi + 2] * 1000f);

        if (uv0 != null && uv0.length >= (idx * 2 + 2)) {
            hash = 31 * hash + Math.round(uv0[idx * 2] * 1000f);
            hash = 31 * hash + Math.round(uv0[idx * 2 + 1] * 1000f);
        }
        return hash;
    }
}
