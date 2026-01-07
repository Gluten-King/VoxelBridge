package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.util.color.ColorMode;

public final class UvRemapUtil {

    private UvRemapUtil() {}

    public static boolean isAtlasEnabled() {
        return ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS;
    }

    public static boolean isColormapMode() {
        return ExportRuntimeConfig.getColorMode() == ColorMode.COLORMAP;
    }

    public static boolean shouldRemap(ExportState state, String spriteKey) {
        if (spriteKey == null) return false;
        if (!isAtlasEnabled()) return false;
        if (ExportRuntimeConfig.isAnimationEnabled() && state.getTextureRepository().hasAnimation(spriteKey)) {
            return false;
        }
        return state.getAtlasBook().containsKey(spriteKey)
            || state.getBlockEntityAtlasPlacements().containsKey(spriteKey);
    }

    public static float[] remapUv(ExportState state, String spriteKey, float u, float v) {
        if (!shouldRemap(state, spriteKey)) {
            return new float[]{u, v};
        }
        return TextureAtlasManager.remapUV(state, spriteKey, 0xFFFFFF, u, v);
    }

    public static float[] remapUvFromPixels(ExportState state, String spriteKey,
                                            float uPx, float vPx, int width, int height) {
        float u = width > 0 ? uPx / (float) width : 0f;
        float v = height > 0 ? vPx / (float) height : 0f;
        return remapUv(state, spriteKey, u, v);
    }
}
