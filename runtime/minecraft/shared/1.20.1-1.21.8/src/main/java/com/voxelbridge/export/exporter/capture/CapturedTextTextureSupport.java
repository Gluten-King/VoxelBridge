package com.voxelbridge.export.exporter.capture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextRenderTypeUtil;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.RenderType;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Shared glyph fallback, diagnostics, and UV policy for captured renderers. */
public final class CapturedTextTextureSupport {
    private CapturedTextTextureSupport() {}

    public static boolean isTextRenderType(RenderType renderType) {
        return TextRenderTypeUtil.isTextRenderType(renderType);
    }

    public static ResolvedTexture resolveFallback(ExportContext context,
                                                  RenderType renderType,
                                                  RenderCaptureUtil.UvStats uvStats,
                                                  ResolvedTexture currentTexture,
                                                  String owner) {
        if (!isTextRenderType(renderType)) return currentTexture;
        var current = currentTexture != null ? currentTexture.texture() : null;
        if (!TextRenderTypeUtil.isDefaultOrMissingLike(current)) return currentTexture;
        var selected = TextRenderTypeUtil.extractFontTexture(renderType);
        // Dynamic glyph page discovery is performed by the runtime texture resolver.
        // No fabricated page is selected when the final RenderType has no binding.
        if (selected == null) return currentTexture;
        VoxelBridgeLogger.info(LogModule.DYNAMIC_MAP,
            "[" + owner + "] Text fallback texture mapped " + current + " -> " + selected);
        return new ResolvedTexture(selected, 0f, 1f, 0f, 1f, false, null, null);
    }

    public static void writeEntityUvs(ExportContext context,
                                      List<RenderCapture.Vertex> vertices,
                                      RenderCaptureUtil.UvStats stats,
                                      boolean atlasUv,
                                      float u0,
                                      float u1,
                                      float v0,
                                      float v1,
                                      String spriteKey,
                                      ResolvedTexture texture,
                                      float[] target) {
        if (atlasUv) {
            RenderCaptureUtil.fillUvsAtlas(vertices, target, u0, u1, v0, v1);
            return;
        }
        if (isFontTexture(texture) && hasPixelUvs(stats)) {
            BufferedImage image = loadTexture(context, texture, "entity:texture-images");
            if (valid(image)) {
                RenderCaptureUtil.fillUvsPixels(vertices, target, image.getWidth(), image.getHeight());
                return;
            }
        }
        RenderCaptureUtil.UvFillResult result = RenderCaptureUtil.fillUvsNormalize(vertices, target, stats);
        if (result.mode() == RenderCaptureUtil.UvFillMode.NORMALIZED) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[UV Normalization] Painting/Entity UV remapped: U[%.3f, %.3f] V[%.3f, %.3f] -> [0,1]x[0,1]",
                result.minU(), result.maxU(), result.minV(), result.maxV()));
        } else if (result.mode() == RenderCaptureUtil.UvFillMode.DEGENERATE) {
            VoxelBridgeLogger.debug(LogModule.ENTITY,
                "[UV Normalization] Degenerate UV detected, using [0,0] for all vertices");
        }
    }

    public static void writeBlockEntityUvs(ExportContext context,
                                           List<RenderCapture.Vertex> vertices,
                                           RenderCaptureUtil.UvStats stats,
                                           boolean atlasUv,
                                           float u0,
                                           float u1,
                                           float v0,
                                           float v1,
                                           String spriteKey,
                                           ResolvedTexture texture,
                                           float[] target) {
        if (atlasUv) RenderCaptureUtil.fillUvsAtlas(vertices, target, u0, u1, v0, v1);
        else RenderCaptureUtil.fillUvsClamp(vertices, target);
        if (!atlasUv && hasPixelUvs(stats) && texture != null) {
            BufferedImage image = context.getCachedSpriteImage(spriteKey);
            if (image == null) image = loadTexture(context, texture, "blockentity:texture-images");
            if (valid(image)) {
                RenderCaptureUtil.fillUvsPixels(vertices, target, image.getWidth(), image.getHeight());
            }
        }
    }

    public static void logUvOnce(ExportContext context,
                                 String scope,
                                 String owner,
                                 RenderType renderType,
                                 RenderCaptureUtil.UvStats stats) {
        if (renderType == null || stats == null || !looksLikeText(renderType)) return;
        String name = renderType.toString();
        if (!context.session().firstOccurrence(scope + "-text-type", name)) return;
        VoxelBridgeLogger.info(LogModule.DYNAMIC_MAP, String.format(
            "[%s] text UV rawU=%s rawV=%s wrappedU=%s wrappedV=%s",
            owner,
            java.util.Arrays.toString(stats.rawU()),
            java.util.Arrays.toString(stats.rawV()),
            java.util.Arrays.toString(stats.wrappedU()),
            java.util.Arrays.toString(stats.wrappedV())));
    }

    public static void logMissingTextureOnce(ExportContext context,
                                             String scope,
                                             String owner,
                                             RenderType renderType) {
        if (!isTextRenderType(renderType)) return;
        String key = String.valueOf(renderType);
        if (!context.session().firstOccurrence(scope + "-text-missing-texture", key)) return;
        VoxelBridgeLogger.warn(LogModule.DYNAMIC_MAP,
            "[" + owner + "] text RenderType texture unresolved: " + key);
    }

    private static boolean looksLikeText(RenderType renderType) {
        String value = renderType.toString().toLowerCase(Locale.ROOT);
        return value.contains("text_") || value.contains("neoforge_text")
            || value.contains("font") || value.contains("glyph");
    }

    private static boolean isFontTexture(ResolvedTexture texture) {
        if (texture == null || texture.texture() == null || texture.texture().getPath() == null) return false;
        String path = texture.texture().getPath().toLowerCase(Locale.ROOT);
        return path.contains("/font/") || path.startsWith("font/");
    }

    private static boolean hasPixelUvs(RenderCaptureUtil.UvStats stats) {
        return stats != null && (stats.maxU() > 1f || stats.maxV() > 1f);
    }

    private static BufferedImage loadTexture(ExportContext context,
                                             ResolvedTexture texture,
                                             String cacheKey) {
        if (texture == null || texture.texture() == null) return null;
        String key = texture.texture().toString();
        ConcurrentHashMap<String, BufferedImage> cache = context.session()
            .computeAttribute(cacheKey, ConcurrentHashMap::new);
        BufferedImage cached = cache.get(key);
        if (cached != null) return cached;
        BufferedImage loaded = context.readTexture(key, true);
        if (loaded != null) cache.put(key, loaded);
        return loaded;
    }

    private static boolean valid(BufferedImage image) {
        return image != null && image.getWidth() > 0 && image.getHeight() > 0;
    }
}
