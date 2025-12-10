package com.voxelbridge.export.util;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

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

        // Cross product: a × b
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

        // Extract RGB components (ignore alpha) and convert sRGB -> linear
        float r = srgbToLinearComponent((argb >> 16) & 0xFF);
        float g = srgbToLinearComponent((argb >> 8) & 0xFF);
        float b = srgbToLinearComponent(argb & 0xFF);
        float a = 1.0f; // Always opaque

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
    public static float[] normalizeUVs(float[] input, TextureAtlasSprite sprite) {
        float[] out = new float[8];
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
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
}
