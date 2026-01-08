package com.voxelbridge.platform.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.fabric.mixin.SpriteContentsAccessor;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.block.MapColor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TextureLoader reads vanilla or resource-pack textures and exposes tint helpers.
 * Aligned with the 1.21.8 NeoForge loading flow (ResourceManager + DynamicTextureReader).
 */
public final class TextureLoader {

    private TextureLoader() {}

    private static final Map<Integer, BufferedImage> MAP_TEXTURES = new ConcurrentHashMap<>();

    public static void registerMapState(int mapId, MapState mapState) {
        if (mapId < 0 || mapState == null) {
            return;
        }
        BufferedImage image = renderMapState(mapState);
        if (image != null) {
            MAP_TEXTURES.put(mapId, image);
            VoxelBridgeLogger.debug(LogModule.MAP,
                String.format("[Map] cached map texture id=%d (%dx%d)", mapId, image.getWidth(), image.getHeight()));
        }
    }

    /**
     * Loads a PNG texture, honoring resource-pack overrides and returning only the first animation frame.
     */
    public static BufferedImage readTexture(Identifier png) {
        return readTexture(png, ExportRuntimeConfig.isAnimationEnabled());
    }

    /**
     * Loads a PNG texture with optional preservation of animation strips.
     */
    public static BufferedImage readTexture(Identifier png, boolean preserveAnimationStrip) {
        boolean logResolve = VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE);
        if (logResolve) {
            VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Resolving %s", png));
        }
        BufferedImage mapImage = tryReadMapTexture(png);
        if (mapImage != null) {
            return mapImage;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            var opt = rm.getResource(png);
            if (opt.isEmpty()) {
                if (logResolve) {
                    VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                        String.format("[TextureLoader][WARN] Missing resource %s", png));
                }
                BufferedImage dynamic = DynamicTextureReader.tryRead(png);
                if (dynamic != null) {
                    if (logResolve) {
                        VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                            String.format("[TextureLoader] Loaded dynamic texture %s (%dx%d)", png,
                                dynamic.getWidth(), dynamic.getHeight()));
                    }
                    return preserveAnimationStrip ? dynamic : extractFirstFrame(dynamic);
                }
                return null;
            }
            try (InputStream in = opt.get().getInputStream()) {
                BufferedImage img = readPngNoColorConversion(in);
                if (logResolve) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                        String.format("[TextureLoader] Loaded %s (%dx%d)", png, img.getWidth(), img.getHeight()));
                }
                if (preserveAnimationStrip) {
                    return img;
                }
                BufferedImage firstFrame = extractFirstFrame(img);
                if (firstFrame != img && logResolve) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                        String.format("[TextureLoader] Extracted first frame for %s -> %dx%d",
                            png, firstFrame.getWidth(), firstFrame.getHeight()));
                }
                return firstFrame;
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.error(LogModule.TEXTURE_RESOLVE,
                String.format("[TextureLoader][ERROR] Failed to read %s: %s", png, t));
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                "[VoxelBridge][WARN] readTexture failed: " + png + " :: " + t);
            return null;
        }
    }

    private static BufferedImage tryReadMapTexture(Identifier id) {
        if (id == null) {
            return null;
        }
        Integer mapId = parseMapId(id.getPath());
        if (mapId == null) {
            return null;
        }
        BufferedImage image = MAP_TEXTURES.get(mapId);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.MAP)) {
            VoxelBridgeLogger.debug(LogModule.MAP, String.format(
                "[Map] lookup id=%d path=%s hit=%s",
                mapId, id.getPath(), image != null));
        }
        return image;
    }

    private static Integer parseMapId(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path;
        if (normalized.startsWith("textures/")) {
            normalized = normalized.substring("textures/".length());
        }
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - ".png".length());
        }
        if (!normalized.startsWith("map/") && !normalized.startsWith("map_")) {
            return null;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BufferedImage renderMapState(MapState mapState) {
        byte[] colors = mapState.colors;
        if (colors == null || colors.length == 0) {
            return null;
        }
        int size = 128;
        if (colors.length != size * size) {
            int root = (int) Math.sqrt(colors.length);
            if (root * root != colors.length) {
                return null;
            }
            size = root;
        }
        NativeImage nativeImg = new NativeImage(size, size, false);
        try {
            int idx = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int color = MapColor.getRenderColor(colors[idx++] & 0xFF);
                    nativeImg.setColor(x, y, color);
                }
            }
            return nativeImageToBufferedImage(nativeImg);
        } finally {
            nativeImg.close();
        }
    }

    /**
     * Reads a PNG using Minecraft's NativeImage to avoid any AWT color management or gamma corrections.
     */
    private static BufferedImage readPngNoColorConversion(InputStream in) throws Exception {
        NativeImage nativeImg = NativeImage.read(in);
        try {
            return nativeImageToBufferedImage(nativeImg);
        } finally {
            nativeImg.close();
        }
    }

    public static BufferedImage fromNativeImage(NativeImage nativeImg) {
        return nativeImageToBufferedImage(nativeImg);
    }

    public static BufferedImage fromSprite(Sprite sprite) {
        if (sprite == null) {
            return null;
        }
        BufferedImage spriteImg = readSpriteContents(sprite);
        if (spriteImg != null) {
            return spriteImg;
        }
        Identifier id = sprite.getContents().getId();
        Identifier pngId = ensureSpritePng(id);
        if (pngId == null) {
            return null;
        }
        return readTexture(pngId, ExportRuntimeConfig.isAnimationEnabled());
    }

    private static BufferedImage nativeImageToBufferedImage(NativeImage nativeImg) {
        int w = nativeImg.getWidth();
        int h = nativeImg.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] allPixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int c = nativeImg.getColor(x, y);
                int a = (c >>> 24) & 0xFF;
                int r = c & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                allPixels[rowOffset + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, allPixels, 0, w);
        return out;
    }

    /**
     * Extracts the first animation frame by taking the top-most square slice.
     */
    public static BufferedImage extractFirstFrame(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();

        if (height <= width) {
            return img;
        }

        int frameSize = Math.min(width, height);
        try {
            return img.getSubimage(0, 0, frameSize, frameSize);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, "[TextureLoader][WARN] Failed to extract first frame: " + e);
            return img;
        }
    }

    private static BufferedImage readSpriteContents(Sprite sprite) {
        try {
            Object contents = sprite.getContents();
            if (!(contents instanceof SpriteContentsAccessor accessor)) {
                return null;
            }
            NativeImage nativeImg = accessor.voxelbridge$getImage();
            if (nativeImg == null) {
                NativeImage[] mipmaps = accessor.voxelbridge$getMipmapLevelsImages();
                if (mipmaps != null && mipmaps.length > 0) {
                    nativeImg = mipmaps[0];
                }
            }
            if (nativeImg == null) {
                return null;
            }
            BufferedImage img = nativeImageToBufferedImage(nativeImg);
            if (ExportRuntimeConfig.isAnimationEnabled()) {
                return img;
            }
            return extractFirstFrame(img);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Converts a sprite key (e.g. "minecraft:block/grass_block_top") to a PNG resource location.
     */
    public static Identifier spriteKeyToTexturePNG(String spriteKey) {
        spriteKey = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(spriteKey);

        int separator = spriteKey.indexOf(':');
        String namespace = separator > 0 ? spriteKey.substring(0, separator) : "minecraft";
        String rawPath = separator > 0 ? spriteKey.substring(separator + 1) : spriteKey;

        if ("blockentity".equals(namespace) || "entity".equals(namespace)) {
            String normalized = rawPath.replace(':', '/');
            normalized = normalized.startsWith("/") ? normalized.substring(1) : normalized;

            int secondSep = normalized.indexOf('/');
            String actualNamespace = secondSep > 0 ? normalized.substring(0, secondSep) : "minecraft";
            String actualPath = secondSep > 0 ? normalized.substring(secondSep + 1) : normalized;

            if (!actualPath.startsWith("textures/")) {
                actualPath = "textures/" + actualPath;
            }

            actualPath = sanitizePath(ensurePngExtension(actualPath));
            actualNamespace = sanitizeNamespace(actualNamespace);
            return Identifier.of(actualNamespace, actualPath);
        }

        String normalizedPath = normalizeSpritePath(rawPath);
        normalizedPath = sanitizePath(normalizedPath);
        String safeNamespace = sanitizePath(namespace);
        return Identifier.of(safeNamespace, normalizedPath);
    }

    private static String normalizeSpritePath(String rawPath) {
        String path = rawPath.replace('\\', '/');
        path = path.startsWith("/") ? path.substring(1) : path;

        if (path.startsWith("textures/")) {
            return ensurePngExtension(path);
        }

        if (path.contains("/")) {
            return ensurePngExtension("textures/" + path);
        }

        return ensurePngExtension("textures/block/" + path);
    }

    private static Identifier ensureSpritePng(Identifier id) {
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        path = ensurePngExtension(path);
        return Identifier.of(id.getNamespace(), path);
    }

    private static String ensurePngExtension(String path) {
        return path.endsWith(".png") ? path : path + ".png";
    }

    /**
     * Sanitizes a resource path to only contain allowed characters.
     */
    private static String sanitizePath(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '/' || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static String sanitizeNamespace(String namespace) {
        StringBuilder sb = new StringBuilder(namespace.length());
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        return out.isEmpty() ? "minecraft" : out;
    }

    /**
     * Converts an ARGB integer color into RGB multipliers in the range [0, 1].
     */
    public static float[] rgbMul(int rgb) {
        return new float[] {
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f
        };
    }
}
