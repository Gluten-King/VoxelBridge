package com.voxelbridge.export.texture;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;

/**
 * Centralized normalization for all dynamic texture paths.
 * <p>
 * RenderType exposes textures as resource-like paths (e.g. {@code textures/default/0.png})
 * that don't match the keys registered in TextureManager (e.g. {@code default/0}).
 * This utility classifies and normalizes such paths so that readTexture can find
 * the correct runtime texture.
 * <p>
 * Map path parsing is inlined here (pure string logic) so that this class has no
 * dependency on {@code MapTextureUtil}, which uses version-specific APIs like
 * {@code DataComponents} and is excluded from 1.20.1 builds.
 */
public final class DynamicTextureUtil {

    private DynamicTextureUtil() {}

    public enum DynamicTextureKind {
        MAP,
        GLYPH,
        STATIC,
        UNKNOWN
    }

    public record NormalizedTexture(ResourceLocation location, DynamicTextureKind kind) {}

    /**
     * Classify and normalize a texture location in a single pass.
     * The returned location is the canonical key usable with TextureManager.
     */
    public static NormalizedTexture normalizeAll(ResourceLocation location) {
        if (location == null) {
            return new NormalizedTexture(null, DynamicTextureKind.UNKNOWN);
        }

        ResourceLocation mapNorm = normalizeDynamicMapLocation(location);
        if (mapNorm != null) {
            debug("Normalized map: " + location + " -> " + mapNorm);
            return new NormalizedTexture(mapNorm, DynamicTextureKind.MAP);
        }

        ResourceLocation glyphNorm = normalizeGlyphLocation(location);
        if (glyphNorm != null) {
            debug("Normalized glyph: " + location + " -> " + glyphNorm);
            return new NormalizedTexture(glyphNorm, DynamicTextureKind.GLYPH);
        }

        if (isLikelyStatic(location)) {
            return new NormalizedTexture(location, DynamicTextureKind.STATIC);
        }

        return new NormalizedTexture(location, DynamicTextureKind.UNKNOWN);
    }

    // =========================================================================
    // Map path normalization (inlined — no version-specific API needed)
    // =========================================================================

    public static ResourceLocation normalizeDynamicMapLocation(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        int id = parseMapId(location);
        if (id < 0) {
            return null;
        }
        return makeResourceLocation(location.getNamespace(), "map/" + id);
    }

    public static int parseMapId(ResourceLocation location) {
        if (location == null) {
            return -1;
        }
        String path = location.getPath();
        if (path == null) {
            return -1;
        }
        if (path.startsWith("map/")) {
            return parseInt(path.substring("map/".length()));
        }
        if (path.startsWith("maps/")) {
            return parseInt(path.substring("maps/".length()));
        }
        if (path.startsWith("textures/dynamic/map/")) {
            String file = path.substring("textures/dynamic/map/".length());
            int dot = file.indexOf('.');
            if (dot > 0) file = file.substring(0, dot);
            int underscore = file.indexOf('_');
            return parseInt(underscore > 0 ? file.substring(0, underscore) : file);
        }
        if (path.startsWith("textures/map/")) {
            String file = path.substring("textures/map/".length());
            int dot = file.indexOf('.');
            if (dot > 0) file = file.substring(0, dot);
            return parseInt(file);
        }
        if (path.startsWith("textures/maps/")) {
            String file = path.substring("textures/maps/".length());
            int dot = file.indexOf('.');
            if (dot > 0) file = file.substring(0, dot);
            return parseInt(file);
        }
        return -1;
    }

    // =========================================================================
    // Glyph path normalization
    // =========================================================================

    /**
     * Normalizes glyph atlas texture locations.
     * <p>
     * Font atlas pages are runtime textures registered in TextureManager under keys like
     * {@code minecraft:default/0}, but RenderType exposes them as resource-like paths
     * such as {@code textures/default/0.png}. This strips the {@code textures/} prefix
     * and {@code .png} suffix to recover the TextureManager key.
     *
     * @return the normalized location, or {@code null} if this is not a glyph path
     */
    public static ResourceLocation normalizeGlyphLocation(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        String path = location.getPath();
        if (path == null) {
            return null;
        }
        String p = path.startsWith("textures/")
            ? path.substring("textures/".length())
            : path;
        if (p.endsWith(".png")) {
            p = p.substring(0, p.length() - ".png".length());
        }
        if (!p.matches("(?:font/)?(?:default|uniform|alt)/\\d+")) {
            return null;
        }
        return makeResourceLocation(location.getNamespace(), p);
    }

    // =========================================================================
    // Glyph pixel cleanup
    // =========================================================================

    private static final int WHITE_THRESHOLD = 250;

    /**
     * Cleans a glyph atlas BufferedImage (TYPE_INT_ARGB) so that:
     * <ul>
     *   <li>Transparent pixels (alpha == 0) get white RGB → rgba(255,255,255,0),
     *       preventing black bleed during texture filtering.</li>
     *   <li>Non-white opaque/semi-transparent pixels have their alpha zeroed out,
     *       removing stray colored pixels that don't belong to the glyph.</li>
     * </ul>
     */
    public static BufferedImage cleanGlyphPixels(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);

        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;

            if (a == 0) {
                pixels[i] = 0x00FFFFFF;
            } else if (r < WHITE_THRESHOLD || g < WHITE_THRESHOLD || b < WHITE_THRESHOLD) {
                pixels[i] = 0x00FFFFFF;
            }
        }

        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isLikelyStatic(ResourceLocation location) {
        String path = location.getPath();
        return path != null && path.startsWith("textures/") && path.endsWith(".png");
    }

    private static ResourceLocation makeResourceLocation(String namespace, String path) {
        return ResourceLocation.tryParse(namespace + ":" + path);
    }

    private static int parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void debug(String message) {
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
            VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP, "[DynamicTextureUtil] " + message);
        }
    }
}
