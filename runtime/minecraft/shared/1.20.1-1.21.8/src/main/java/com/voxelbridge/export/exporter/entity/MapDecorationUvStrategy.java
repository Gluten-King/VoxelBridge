package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.compat.AtlasCompat;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/** Exact-runtime map-decoration atlas lookup and pixel-UV normalization. */
final class MapDecorationUvStrategy {
    private static final String CACHE_KEY = "entity:map-decoration-atlas-size";

    private MapDecorationUvStrategy() {}

    static RenderCaptureUtil.UvStats normalize(ExportContext context,
                                               RenderCaptureUtil.UvStats uvStats,
                                               RenderType renderType) {
        if (uvStats == null || renderType == null) return uvStats;
        ResourceLocation texture = RenderTypeTextureResolver.INSTANCE.resolve(renderType);
        if (texture == null || texture.getPath() == null || !texture.getPath().contains("map_decorations")) {
            return uvStats;
        }
        boolean pixelUv = uvStats.maxU() > 1.1f || uvStats.maxV() > 1.1f
            || uvStats.minU() < -0.1f || uvStats.minV() < -0.1f;
        if (!pixelUv) return uvStats;

        int[] size = atlasSize(context, texture);
        if (size == null) return uvStats;
        RenderCaptureUtil.UvStats normalized = RenderCaptureUtil.normalizeUvStatsPixels(uvStats, size[0], size[1]);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
            VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                "[MapDecor] Normalized UVs using atlas size " + size[0] + "x" + size[1]
                    + " atlas=" + texture);
        }
        return normalized != null ? normalized : uvStats;
    }

    private static int[] atlasSize(ExportContext context, ResourceLocation atlasLocation) {
        int[] cached = (int[]) context.session().attribute(CACHE_KEY);
        if (cached != null) return cached;
        try {
            var texture = ClientAccessHolder.get().getTextureManager().getTexture(atlasLocation);
            if (!(texture instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) return null;
            int widthSum = 0;
            int heightSum = 0;
            int count = 0;
            for (TextureAtlasSprite sprite : AtlasCompat.getAllSprites(atlas)) {
                if (sprite == null || sprite.contents() == null) continue;
                int spriteWidth = sprite.contents().width();
                int spriteHeight = sprite.contents().height();
                float widthFraction = sprite.getU1() - sprite.getU0();
                float heightFraction = sprite.getV1() - sprite.getV0();
                if (spriteWidth <= 0 || spriteHeight <= 0
                    || widthFraction <= 1e-6f || heightFraction <= 1e-6f) continue;
                int width = Math.round(spriteWidth / widthFraction);
                int height = Math.round(spriteHeight / heightFraction);
                if (width <= 0 || height <= 0) continue;
                widthSum += width;
                heightSum += height;
                if (++count >= 8) break;
            }
            if (count == 0) return null;
            int[] resolved = {Math.round(widthSum / (float) count), Math.round(heightSum / (float) count)};
            context.session().putAttribute(CACHE_KEY, resolved);
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
