package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;

public final class UvRemapUtil {

    private UvRemapUtil() {}

    public static boolean isAtlasEnabled() {
        return ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS;
    }

    public static boolean isColormapMode() {
        return ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP;
    }

    public static boolean shouldRemap(ExportContext ctx, String spriteKey) {
        if (spriteKey == null) return false;
        if (!isAtlasEnabled()) return false;
        if (ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(spriteKey)) {
            return false;
        }
        return ctx.getAtlasBook().containsKey(spriteKey)
            || ctx.getBlockEntityAtlasPlacements().containsKey(spriteKey);
    }

    public static float[] remapUv(ExportContext ctx, String spriteKey, float u, float v) {
        if (!shouldRemap(ctx, spriteKey)) {
            return new float[]{u, v};
        }
        return TextureAtlasManager.remapUV(ctx, spriteKey, 0xFFFFFF, u, v);
    }

    public static float[] remapUvFromPixels(ExportContext ctx, String spriteKey,
                                            float uPx, float vPx, int width, int height) {
        float u = width > 0 ? uPx / (float) width : 0f;
        float v = height > 0 ? vPx / (float) height : 0f;
        return remapUv(ctx, spriteKey, u, v);
    }
}
