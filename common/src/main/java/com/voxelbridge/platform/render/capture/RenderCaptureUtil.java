package com.voxelbridge.platform.render.capture;

import com.voxelbridge.core.ir.TintMode;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.core.util.color.ColorModeHandler;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Shared helpers for render-capture UV handling and atlas resolution.
 */
public final class RenderCaptureUtil {
    private RenderCaptureUtil() {}

    public record UvStats(float[] rawU, float[] rawV,
                          float minU, float maxU,
                          float minV, float maxV,
                          float[] wrappedU, float[] wrappedV) {}

    public record ColorModeResult(float[] uv1, TintMode tintMode) {}

    public enum UvFillMode {
        NORMALIZED,
        DEGENERATE,
        CLAMPED
    }

    public record UvFillResult(UvFillMode mode, float minU, float maxU, float minV, float maxV) {}

    public static UvStats computeUvStats(List<RenderCapture.Vertex> verts) {
        int count = Math.min(4, verts.size());
        float[] rawU = new float[count];
        float[] rawV = new float[count];
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            rawU[i] = verts.get(i).u;
            rawV[i] = verts.get(i).v;
            minU = Math.min(minU, rawU[i]);
            maxU = Math.max(maxU, rawU[i]);
            minV = Math.min(minV, rawV[i]);
            maxV = Math.max(maxV, rawV[i]);
        }
        float[] wrappedU = new float[count];
        float[] wrappedV = new float[count];
        for (int i = 0; i < count; i++) {
            wrappedU[i] = wrap01(rawU[i]);
            wrappedV[i] = wrap01(rawV[i]);
        }
        return new UvStats(rawU, rawV, minU, maxU, minV, maxV, wrappedU, wrappedV);
    }

    public static UvStats normalizeUvStatsPixels(UvStats uvStats, int width, int height) {
        if (uvStats == null) {
            return null;
        }
        float invW = width <= 0 ? 1f : 1f / width;
        float invH = height <= 0 ? 1f : 1f / height;
        int count = uvStats.rawU().length;
        float[] rawU = new float[count];
        float[] rawV = new float[count];
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            rawU[i] = uvStats.rawU()[i] * invW;
            rawV[i] = uvStats.rawV()[i] * invH;
            minU = Math.min(minU, rawU[i]);
            maxU = Math.max(maxU, rawU[i]);
            minV = Math.min(minV, rawV[i]);
            maxV = Math.max(maxV, rawV[i]);
        }
        float[] wrappedU = new float[count];
        float[] wrappedV = new float[count];
        for (int i = 0; i < count; i++) {
            wrappedU[i] = wrap01(rawU[i]);
            wrappedV[i] = wrap01(rawV[i]);
        }
        return new UvStats(rawU, rawV, minU, maxU, minV, maxV, wrappedU, wrappedV);
    }

    public static ResolvedTexture resolveAtlasSprite(ResolvedTexture textureRes,
                                                     AtlasLocator locator,
                                                     UvStats uvStats,
                                                     Identifier atlasLocation) {
        if (textureRes == null || locator == null || uvStats == null) {
            return textureRes;
        }
        if (!textureRes.isAtlasTexture() || textureRes.sprite() != null) {
            return textureRes;
        }
        float centerU = average(uvStats.wrappedU());
        float centerV = average(uvStats.wrappedV());
        Identifier atlas = atlasLocation != null ? atlasLocation : textureRes.texture();
        TextureAtlasSprite located = locator.find(atlas, centerU, centerV);
        if (located == null) {
            return textureRes;
        }
        return new ResolvedTexture(
            located.contents().name(),
            located.getU0(), located.getU1(),
            located.getV0(), located.getV1(),
            true,
            located,
            atlas
        );
    }

    public static void fillUvsAtlas(List<RenderCapture.Vertex> verts, float[] uv0,
                                    float u0, float u1, float v0, float v1) {
        int count = Math.min(4, verts.size());
        float du = u1 - u0;
        float dv = v1 - v0;
        for (int i = 0; i < count; i++) {
            RenderCapture.Vertex v = verts.get(i);
            float su = (du == 0f) ? 0f : (v.u - u0) / du;
            float sv = (dv == 0f) ? 0f : (v.v - v0) / dv;
            su = Math.max(0f, Math.min(1f, su));
            sv = Math.max(0f, Math.min(1f, sv));
            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }
    }

    public static void fillUvsClamp(List<RenderCapture.Vertex> verts, float[] uv0) {
        int count = Math.min(4, verts.size());
        for (int i = 0; i < count; i++) {
            RenderCapture.Vertex v = verts.get(i);
            float su = Math.max(0f, Math.min(1f, v.u));
            float sv = Math.max(0f, Math.min(1f, v.v));
            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }
    }

    public static UvFillResult fillUvsNormalize(List<RenderCapture.Vertex> verts, float[] uv0, UvStats uvStats) {
        int count = Math.min(4, verts.size());
        UvStats stats = uvStats != null ? uvStats : computeUvStats(verts);

        float minU = stats.minU();
        float maxU = stats.maxU();
        float minV = stats.minV();
        float maxV = stats.maxV();

        float rangeU = maxU - minU;
        float rangeV = maxV - minV;

        boolean needsNormalization =
            maxU > 1.1f || minU < -0.1f || maxV > 1.1f || minV < -0.1f ||
            rangeU < 1e-6f || rangeV < 1e-6f;

        if (needsNormalization && rangeU > 1e-6f && rangeV > 1e-6f) {
            for (int i = 0; i < count; i++) {
                RenderCapture.Vertex v = verts.get(i);
                float su = (v.u - minU) / rangeU;
                float sv = (v.v - minV) / rangeV;
                uv0[i * 2] = Math.max(0f, Math.min(1f, su));
                uv0[i * 2 + 1] = Math.max(0f, Math.min(1f, sv));
            }
            return new UvFillResult(UvFillMode.NORMALIZED, minU, maxU, minV, maxV);
        }

        if (rangeU < 1e-6f || rangeV < 1e-6f) {
            for (int i = 0; i < count; i++) {
                uv0[i * 2] = 0f;
                uv0[i * 2 + 1] = 0f;
            }
            return new UvFillResult(UvFillMode.DEGENERATE, minU, maxU, minV, maxV);
        }

        for (int i = 0; i < count; i++) {
            RenderCapture.Vertex v = verts.get(i);
            float su = Math.max(0f, Math.min(1f, v.u));
            float sv = Math.max(0f, Math.min(1f, v.v));
            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }
        return new UvFillResult(UvFillMode.CLAMPED, minU, maxU, minV, maxV);
    }

    public static void fillUvsPixels(List<RenderCapture.Vertex> verts, float[] uv0, int width, int height) {
        int count = Math.min(4, verts.size());
        float invW = width <= 0 ? 1f : 1f / width;
        float invH = height <= 0 ? 1f : 1f / height;
        for (int i = 0; i < count; i++) {
            RenderCapture.Vertex v = verts.get(i);
            float su = v.u * invW;
            float sv = v.v * invH;
            su = Math.max(0f, Math.min(1f, su));
            sv = Math.max(0f, Math.min(1f, sv));
            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }
    }

    public static ColorModeResult applyColorMode(ExportContext ctx, float[] colors, float[] emptyUv) {
        ColorMode mode = ctx.getColorMode();
        TintMode tintMode = mode != null && mode.usesColormap()
            ? TintMode.COLORMAP
            : TintMode.VERTEX_COLOR;
        if (mode == null || !mode.usesColormap()) {
            return new ColorModeResult(emptyUv, tintMode);
        }
        int argb = toArgb(colors);
        boolean hasTint = !isWhite(colors);
        ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(
            mode, ctx.getColorMapAccess(), argb, hasTint);
        float[] uv1 = colorData.uv1() != null ? colorData.uv1() : emptyUv;
        if (colorData.colors() != null && colors != null) {
            System.arraycopy(colorData.colors(), 0, colors, 0, colors.length);
        }
        return new ColorModeResult(uv1, tintMode);
    }

    public static int toArgb(float[] colors) {
        float r = colors.length >= 1 ? colors[0] : 1f;
        float g = colors.length >= 2 ? colors[1] : 1f;
        float b = colors.length >= 3 ? colors[2] : 1f;
        int ri = (int) (Math.max(0f, Math.min(1f, r)) * 255f + 0.5f);
        int gi = (int) (Math.max(0f, Math.min(1f, g)) * 255f + 0.5f);
        int bi = (int) (Math.max(0f, Math.min(1f, b)) * 255f + 0.5f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    public static boolean isWhite(float[] colors) {
        if (colors == null || colors.length < 3) return true;
        float eps = 1e-3f;
        return Math.abs(colors[0] - 1f) < eps &&
               Math.abs(colors[1] - 1f) < eps &&
               Math.abs(colors[2] - 1f) < eps;
    }

    public static float average(float[] arr) {
        if (arr == null || arr.length == 0) return 0f;
        float sum = 0f;
        for (float v : arr) sum += v;
        return sum / arr.length;
    }

    public static float wrap01(float v) {
        float wrapped = v % 1f;
        if (wrapped < 0f) wrapped += 1f;
        return wrapped;
    }

    /**
     * Converts a face normal to the closest Minecraft {@link Direction},
     * or {@code null} if the normal is zero/degenerate.
     */
    public static Direction approximateDirection(float[] normal) {
        int axis = GeometryUtil.dominantAxisSigned(normal);
        return switch (axis) {
            case  1 -> Direction.EAST;
            case -1 -> Direction.WEST;
            case  2 -> Direction.UP;
            case -2 -> Direction.DOWN;
            case  3 -> Direction.SOUTH;
            case -3 -> Direction.NORTH;
            default -> null;
        };
    }
}
