package com.voxelbridge.export.texture;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.texture.UvRemap;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

public final class UvRemapUtil {

    private UvRemapUtil() {}

    private static final int DEFAULT_TINT = 0xFFFFFF;

    public static boolean isAtlasEnabled(ExportOptions options) {
        return options != null && options.atlasMode() == ExportOptions.AtlasMode.ATLAS;
    }

    public static boolean isColormapMode(ExportOptions options) {
        if (options == null) {
            return false;
        }
        ColorMode mode = options.colorMode();
        return mode != null && mode.usesColormap();
    }

    public static boolean shouldRemap(ExportState state, String spriteKey, ExportOptions options) {
        if (spriteKey == null) return false;
        if (!isAtlasEnabled(options)) return false;
        if (options != null && options.animationEnabled()
            && state.getTextureRepository().hasAnimation(spriteKey)) {
            return false;
        }
        return state.getAtlasBook().containsKey(spriteKey)
            || state.getBlockEntityAtlasPlacements().containsKey(spriteKey);
    }

    public static float[] remapUv(ExportState state, String spriteKey, float u, float v, ExportOptions options) {
        return remapUv(state, spriteKey, DEFAULT_TINT, u, v, options);
    }

    public static float[] remapUv(ExportState state, String spriteKey, int tint, float u, float v, ExportOptions options) {
        if (!shouldRemap(state, spriteKey, options)) {
            return new float[]{u, v};
        }
        return remapUvUsingAtlas(state, spriteKey, tint, u, v, options);
    }

    public static float[] remapUvFromPixels(ExportState state, String spriteKey,
                                            float uPx, float vPx, int width, int height, ExportOptions options) {
        float u = width > 0 ? uPx / (float) width : 0f;
        float v = height > 0 ? vPx / (float) height : 0f;
        return remapUv(state, spriteKey, u, v, options);
    }

    private static float[] remapUvUsingAtlas(ExportState state, String spriteKey, int tint, float u, float v, ExportOptions options) {
        boolean animated = options != null && options.animationEnabled()
            && state.getTextureRepository().hasAnimation(spriteKey);
        if (!isAtlasEnabled(options) || animated) {
            return new float[]{u, v};
        }

        int normalizedTint = sanitizeTintValue(tint);
        ExportState.TintAtlas atlas = state.getAtlasBook().get(spriteKey);
        if (atlas == null) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][WARN] No atlas found for %s", spriteKey));
            return new float[]{u, v};
        }
        if (atlas.placements.isEmpty()) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][WARN] Atlas for %s has no placements", spriteKey));
            return new float[]{u, v};
        }
        Integer tintIndexObj = atlas.tintToIndex.get(normalizedTint);
        int tintIndex = tintIndexObj != null ? tintIndexObj : 0;
        if (tintIndexObj == null) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format(
                "[RemapUV][WARN] Missing tint index for %s tint=%06X; using index 0",
                spriteKey, normalizedTint));
        }

        ExportState.TexturePlacement placement = atlas.placements.getOrDefault(tintIndex, atlas.placements.get(0));
        if (placement == null) {
            placement = atlas.placements.values().stream().findFirst().orElse(null);
        }
        if (placement == null) {
            VoxelBridgeLogger.error(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][ERROR] No placement for %s tintIndex=%d", spriteKey, tintIndex));
            return new float[]{u, v};
        }

        return UvRemap.remap(placement, u, v);
    }

    private static int sanitizeTintValue(int tint) {
        if (tint == -1) {
            return DEFAULT_TINT;
        }
        return tint & 0xFFFFFF;
    }
}
