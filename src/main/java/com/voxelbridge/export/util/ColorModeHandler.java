package com.voxelbridge.export.util;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.ColorMapManager;

/**
 * Handles color mode logic for quad exporters.
 * Supports both VERTEX_COLOR and COLORMAP modes.
 */
public final class ColorModeHandler {

    private ColorModeHandler() {
        // Utility class - prevent instantiation
    }

    /**
     * Data class holding color-related information for quad output.
     */
    public static class ColorData {
        /** UV coordinates for colormap texture (null if using vertex colors) */
        public final float[] uv1;

        /** Vertex colors (RGBA for 4 vertices) */
        public final float[] colors;

        public ColorData(float[] uv1, float[] colors) {
            this.uv1 = uv1;
            this.colors = colors;
        }
    }

    /**
     * Prepares color data based on current color mode.
     *
     * @param ctx export context
     * @param argb ARGB color value
     * @param hasTint whether tint should be applied
     * @return ColorData containing uv1 and colors
     */
    public static ColorData prepareColors(ExportContext ctx, int argb, boolean hasTint) {
        if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.VERTEX_COLOR) {
            // VertexColor mode: colors in COLOR_0
            return new ColorData(
                null,  // no TEXCOORD_1
                GeometryUtil.computeVertexColors(argb, hasTint)
            );
        } else {
            // ColorMap mode: use TEXCOORD_1
            float[] colorUv = getColormapUV(ctx, argb);
            return new ColorData(
                colorUv,
                GeometryUtil.whiteColor()
            );
        }
    }

    /**
     * Prepares color data with UV remapping (for QuadCollector).
     *
     * @param ctx export context
     * @param argb ARGB color value
     * @param normalizedUVs normalized UV coordinates [0,1] for 4 vertices
     * @return ColorData containing uv1 and colors
     */
    public static ColorData prepareColorsWithUV(ExportContext ctx, int argb, float[] normalizedUVs) {
        if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.VERTEX_COLOR) {
            // VertexColor mode: colors in COLOR_0
            return new ColorData(
                null,  // no TEXCOORD_1
                GeometryUtil.computeVertexColors(argb, true)  // fluids always have color
            );
        } else {
            // ColorMap mode: remap UVs to colormap texture
            float[] lut = ColorMapManager.remapColorUV(ctx, argb);
            float u0 = lut[0], v0 = lut[1], u1 = lut[2], v1 = lut[3];
            float du = u1 - u0, dv = v1 - v0;

            float[] uv1 = new float[8];
            for (int i = 0; i < 4; i++) {
                uv1[i * 2] = u0 + normalizedUVs[i * 2] * du;
                uv1[i * 2 + 1] = v0 + normalizedUVs[i * 2 + 1] * dv;
            }

            return new ColorData(
                uv1,
                GeometryUtil.whiteColor()
            );
        }
    }

    /**
     * Gets colormap UV coordinates for a color value using ColorMapManager.
     *
     * @param ctx export context
     * @param argb ARGB color value
     * @return 8 floats representing UV coordinates for 4 vertices (quad format)
     */
    private static float[] getColormapUV(ExportContext ctx, int argb) {
        var p = ColorMapManager.registerColor(ctx, argb);
        return new float[]{
            p.u0(), p.v0(),  // vertex 0
            p.u1(), p.v0(),  // vertex 1
            p.u1(), p.v1(),  // vertex 2
            p.u0(), p.v1()   // vertex 3
        };
    }
}
