package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.resource.PbrResourceCandidates;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;

/**
 * Shared helper to locate and cache PBR companion textures (_n / _s) for a given sprite key.
 * This is a lightweight version of the BlockExporter logic, reused by fluids and BER paths.
 */
public final class PbrTextureHelper {
    private PbrTextureHelper() {}

    /**
     * Default PBR color values used when actual PBR textures are missing.
     */
    public static final int DEFAULT_NORMAL_COLOR = PbrImages.DEFAULT_NORMAL_COLOR;
    public static final int DEFAULT_SPECULAR_COLOR = PbrImages.DEFAULT_SPECULAR_COLOR;

    public record PbrResult(BufferedImage normalImage, BufferedImage specularImage,
                            ResourceLocation normalLocation, ResourceLocation specularLocation) {}

    /**
     * Internal result type for PBR texture loading with location tracking.
     */
    private record PbrLoadResult(BufferedImage image, ResourceLocation location) {}

    /**
     * Attempts to locate and cache normal/specular maps for the given sprite.
     * Uses enhanced fallback logic to handle non-standard resource pack layouts.
     */
    public static PbrResult ensurePbrCached(ExportContext ctx, String spriteKey, TextureAtlasSprite sprite) {
        if (spriteKey == null) {
            return new PbrResult(null, null, null, null);
        }

        String normalKey = spriteKey + "_n";
        String specKey = spriteKey + "_s";

        BufferedImage normalCached = ctx.getCachedSpriteImage(normalKey);
        BufferedImage normalImg = sanitizeMissingNo(normalCached, DEFAULT_NORMAL_COLOR, normalKey);
        if (normalImg != null && normalImg != normalCached) {
            ctx.cacheSpriteImage(normalKey, normalImg);
        }
        BufferedImage specCached = ctx.getCachedSpriteImage(specKey);
        BufferedImage specImg = sanitizeMissingNo(specCached, DEFAULT_SPECULAR_COLOR, specKey);
        if (specImg != null && specImg != specCached) {
            ctx.cacheSpriteImage(specKey, specImg);
        }

        ResourceLocation normalLoc = null;
        ResourceLocation specLoc = null;

        // Load normal if missing
        if (normalImg == null && sprite != null && sprite.contents() != null) {
            ResourceLocation baseLoc = sprite.contents().name();
            PbrLoadResult normalResult = tryLoadPbrResourceRobustWithLocation(ctx, baseLoc, "_n");
            if (normalResult.image != null) {
                normalImg = sanitizeMissingNo(normalResult.image, DEFAULT_NORMAL_COLOR, normalKey);
                normalLoc = normalResult.location;
                if (normalImg != null) {
                    ctx.cacheSpriteImage(normalKey, normalImg);
                    VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[PBR] Cached normal for " + spriteKey + " -> " + normalLoc);
                }
            }
        }

        // Load specular if missing
        if (specImg == null && sprite != null && sprite.contents() != null) {
            ResourceLocation baseLoc = sprite.contents().name();
            PbrLoadResult specResult = tryLoadPbrResourceRobustWithLocation(ctx, baseLoc, "_s");
            if (specResult.image != null) {
                specImg = sanitizeMissingNo(specResult.image, DEFAULT_SPECULAR_COLOR, specKey);
                specLoc = specResult.location;
                if (specImg != null) {
                    ctx.cacheSpriteImage(specKey, specImg);
                    VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[PBR] Cached specular for " + spriteKey + " -> " + specLoc);
                }
            }
        }

        return new PbrResult(normalImg, specImg, normalLoc, specLoc);
    }

    /**
     * Enhanced PBR texture lookup with fallback strategies.
     * Handles non-standard resource pack layouts by trying multiple candidate paths.
     * Returns both the loaded image and its ResourceLocation.
     */
    private static PbrLoadResult tryLoadPbrResourceRobustWithLocation(ExportContext ctx, ResourceLocation spriteName, String suffix) {
        if (spriteName == null || suffix == null) return new PbrLoadResult(null, null);

        ResourceId base = new ResourceId(spriteName.getNamespace(), spriteName.getPath());
        for (ResourceId candidate : PbrResourceCandidates.candidates(base, suffix)) {
            ResourceLocation loc = new ResourceLocation(candidate.namespace(), candidate.path());
            BufferedImage result = ctx.session().runtime().textures().readTexture(candidate, false);
            if (result != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[PBR] Found %s at: %s", suffix, loc));
                return new PbrLoadResult(result, loc);
            }
        }

        return new PbrLoadResult(null, null);
    }

    public static BufferedImage sanitizeMissingNo(BufferedImage img, int defaultColor, String cacheKey) {
        BufferedImage sanitized = PbrImages.sanitizeMissingTexture(img, defaultColor);
        if (sanitized != null && sanitized != img) {
            VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format(
                "[PBR] Detected missingno placeholder for %s, replaced with default color", cacheKey));
        }
        return sanitized;
    }
}
