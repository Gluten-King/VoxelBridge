package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.ExportLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;

/**
 * Shared helper to locate and cache PBR companion textures (_n / _s) for a given sprite key.
 * This is a lightweight version of the BlockExporter logic, reused by fluids and BER paths.
 */
@OnlyIn(Dist.CLIENT)
public final class PbrTextureHelper {
    private PbrTextureHelper() {}

    /**
     * Default PBR color values used when actual PBR textures are missing.
     */
    public static final int DEFAULT_NORMAL_COLOR = 0xFF8080FF;   // RGB(128, 128, 255) - neutral normal map
    public static final int DEFAULT_SPECULAR_COLOR = 0x00000000; // Transparent black - no specularity

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

        BufferedImage normalImg = ctx.getCachedSpriteImage(normalKey);
        BufferedImage specImg = ctx.getCachedSpriteImage(specKey);

        ResourceLocation normalLoc = null;
        ResourceLocation specLoc = null;

        // Load normal if missing
        if (normalImg == null && sprite != null && sprite.contents() != null) {
            ResourceLocation baseLoc = sprite.contents().name();
            PbrLoadResult normalResult = tryLoadPbrResourceRobustWithLocation(baseLoc, "_n");
            if (normalResult.image != null) {
                normalImg = normalResult.image;
                normalLoc = normalResult.location;
                ctx.cacheSpriteImage(normalKey, normalImg);
                ExportLogger.log("[PBR] Cached normal for " + spriteKey + " -> " + normalLoc);
            }
        }

        // Load specular if missing
        if (specImg == null && sprite != null && sprite.contents() != null) {
            ResourceLocation baseLoc = sprite.contents().name();
            PbrLoadResult specResult = tryLoadPbrResourceRobustWithLocation(baseLoc, "_s");
            if (specResult.image != null) {
                specImg = specResult.image;
                specLoc = specResult.location;
                ctx.cacheSpriteImage(specKey, specImg);
                ExportLogger.log("[PBR] Cached specular for " + spriteKey + " -> " + specLoc);
            }
        }

        return new PbrResult(normalImg, specImg, normalLoc, specLoc);
    }

    /**
     * Enhanced PBR texture lookup with fallback strategies.
     * Handles non-standard resource pack layouts by trying multiple candidate paths.
     * Returns both the loaded image and its ResourceLocation.
     */
    private static PbrLoadResult tryLoadPbrResourceRobustWithLocation(ResourceLocation spriteName, String suffix) {
        if (spriteName == null || suffix == null) return new PbrLoadResult(null, null);

        String namespace = spriteName.getNamespace();
        String path = spriteName.getPath();

        // Build candidate paths in priority order
        java.util.List<String> candidates = new java.util.ArrayList<>();

        // 1. Direct: same location as base texture
        candidates.add(buildPbrPath(path, suffix));

        // 2. If it's a CTM variant (ends with /digit), try parent directory
        if (path.matches(".*/\\d+$")) {
            String parent = path.replaceAll("/\\d+$", "");
            candidates.add(buildPbrPath(parent, suffix));
        }

        // 3. If it has _overlay suffix, try base name
        if (path.contains("_overlay")) {
            String base = path.replaceAll("_overlay$", "");
            candidates.add(buildPbrPath(base, suffix));
        }

        // 4. If it has numeric suffix, try without it
        if (path.matches(".*_\\d+$")) {
            String base = path.replaceAll("_\\d+$", "");
            candidates.add(buildPbrPath(base, suffix));
        }

        // 5. If it's in a CTM/connected subdirectory, try base block texture
        if (path.contains("/ctm/") || path.contains("/connected/")) {
            String baseName = extractCtmBaseName(path);
            if (baseName != null) {
                candidates.add(buildPbrPath(baseName, suffix));
            }
        }

        // Try each candidate
        for (String candidate : candidates) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(namespace, candidate);
            BufferedImage result = TextureLoader.readTexture(loc);
            if (result != null) {
                ExportLogger.log(String.format("[PBR] Found %s at: %s", suffix, loc));
                return new PbrLoadResult(result, loc);
            }
        }

        return new PbrLoadResult(null, null);
    }

    /**
     * Build PBR texture path, handling special cases.
     */
    private static String buildPbrPath(String basePath, String suffix) {
        if (basePath.startsWith("optifine/cit/")) {
            return basePath + suffix + ".png";
        }
        if (basePath.startsWith("textures/")) {
            return basePath + suffix + ".png";
        }
        return "textures/" + basePath + suffix + ".png";
    }

    /**
     * Extract base block name from CTM path.
     */
    private static String extractCtmBaseName(String path) {
        String pattern = "^(block|entity)/(ctm|connected|continuity)/([^/]+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(path);

        if (m.find()) {
            String prefix = m.group(1);
            String name = m.group(3);
            return prefix + "/" + name;
        }

        return null;
    }
}
