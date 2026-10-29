package com.voxelbridge.core.util.color;

/**
 * Lightweight color utilities that are independent of Minecraft APIs.
 */
public final class ColorUtil {

    private ColorUtil() {}

    /**
     * Converts an ARGB integer color into RGB multipliers in the range [0, 1].
     */
    public static float[] rgbMul(int rgb) {
        return new float[] {
            ((rgb >> 16) & 0xFF) / 255f,
            ((rgb >> 8) & 0xFF) / 255f,
            (rgb & 0xFF) / 255f
        };
    }

    public static boolean hasBakedColors(int[] colors) {
        if (colors == null) {
            return false;
        }
        for (int c : colors) {
            if (c != 0xFFFFFFFF && c != -1) {
                return true;
            }
        }
        return false;
    }

    public static int extractBakedTintArgb(int[] argbColors) {
        if (argbColors == null) {
            return 0xFFFFFFFF;
        }
        for (int c : argbColors) {
            if (c != 0xFFFFFFFF && c != -1) {
                return c;
            }
        }
        return 0xFFFFFFFF;
    }

    public static float[] convertArgbToLinearRgba(int[] argbColors) {
        float[] out = new float[16];
        if (argbColors == null) {
            return out;
        }
        for (int i = 0; i < 4; i++) {
            int c = argbColors[i];
            float r = srgbToLinearComponent((c >> 16) & 0xFF);
            float g = srgbToLinearComponent((c >> 8) & 0xFF);
            float b = srgbToLinearComponent(c & 0xFF);
            float a = ((c >> 24) & 0xFF) / 255.0f;

            int base = i * 4;
            out[base] = r;
            out[base + 1] = g;
            out[base + 2] = b;
            out[base + 3] = a;
        }
        return out;
    }

    /**
     * Removes neutral per-vertex shading from captured renderer colors while preserving alpha and
     * genuinely chromatic tint. CPU fallbacks for instanced block-entity renderers (notably
     * Flywheel) bake ambient/directional light into the vertex RGB channel. glTF applies lighting
     * again, so retaining that neutral multiplier makes nominally white textures appear gray.
     *
     * <p>Only non-black achromatic values are promoted to white. Colored values and true black
     * remain untouched; callers must also exclude glyph/text geometry where gray is meaningful.</p>
     */
    public static void removeNeutralVertexShading(float[] colors) {
        if (colors == null) {
            return;
        }
        final float channelTolerance = 1.5f / 255f;
        for (int base = 0; base + 2 < colors.length; base += 4) {
            float r = colors[base];
            float g = colors[base + 1];
            float b = colors[base + 2];
            float min = Math.min(r, Math.min(g, b));
            float max = Math.max(r, Math.max(g, b));
            if (max >= 0.1f && max < 1f - channelTolerance && max - min <= channelTolerance) {
                colors[base] = 1f;
                colors[base + 1] = 1f;
                colors[base + 2] = 1f;
            }
        }
    }

    private static float srgbToLinearComponent(int c) {
        float v = c / 255.0f;
        if (v <= 0.04045f) {
            return v / 12.92f;
        }
        return (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }
}
