package com.voxelbridge.voxy.client.core.model;

public final class ColorSRGB {
    private ColorSRGB() {}

    public static float srgbToLinear(int channel) {
        float c = Math.max(0.0f, Math.min(1.0f, channel / 255.0f));
        if (c <= 0.04045f) {
            return c / 12.92f;
        }
        return (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    public static int linearToSrgb(float r, float g, float b, float a) {
        int ri = linearToSrgbChannel(r);
        int gi = linearToSrgbChannel(g);
        int bi = linearToSrgbChannel(b);
        float alpha = a;
        if (alpha > 1.0f) {
            alpha = alpha / 255.0f;
        }
        int ai = linearToSrgbChannel(alpha);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    public static int linearToSrgbChannel(float c) {
        c = Math.max(0.0f, Math.min(1.0f, c));
        if (c <= 0.0031308f) {
            c = 12.92f * c;
        } else {
            c = 1.055f * (float) Math.pow(c, 1.0f / 2.4f) - 0.055f;
        }
        return Math.min(255, Math.max(0, Math.round(c * 255.0f)));
    }
}
