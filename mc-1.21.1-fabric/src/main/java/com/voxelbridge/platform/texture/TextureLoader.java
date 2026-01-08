package com.voxelbridge.platform.texture;

import net.minecraft.client.texture.NativeImage;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * TextureLoader reads vanilla or resource-pack textures and exposes tint helpers.
 */
public final class TextureLoader {

    private TextureLoader() {}

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
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            var opt = rm.getResource(png);
            if (opt.isEmpty()) {
                if (logResolve) {
                    VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader][WARN] Missing resource %s", png));
                }
                BufferedImage dynamic = DynamicTextureReader.tryRead(png);
                if (dynamic != null) {
                    if (logResolve) {
                        VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Loaded dynamic texture %s (%dx%d)", png, dynamic.getWidth(), dynamic.getHeight()));
                    }
                    return preserveAnimationStrip ? dynamic : extractFirstFrame(dynamic);
                }
                return null;
            }
            try (InputStream in = opt.get().getInputStream()) {
                BufferedImage img = readPngNoColorConversion(in);
                if (logResolve) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Loaded %s (%dx%d)", png, img.getWidth(), img.getHeight()));
                }
                if (preserveAnimationStrip) {
                    return img;
                }
                BufferedImage firstFrame = extractFirstFrame(img);
                if (firstFrame != img && logResolve) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Extracted first frame for %s -> %dx%d",
                        png, firstFrame.getWidth(), firstFrame.getHeight()));
                }
                return firstFrame;
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.error(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader][ERROR] Failed to read %s: %s", png, t));
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, "[VoxelBridge][WARN] readTexture failed: " + png + " :: " + t);
            return null;
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

    public static BufferedImage fromSprite(Sprite sprite) {
        if (sprite == null) {
            return null;
        }
        Identifier id = sprite.getContents().getId();
        BufferedImage img = readTexture(id, ExportRuntimeConfig.isAnimationEnabled());
        if (img == null) {
            Identifier pngId = ensureSpritePng(id);
            if (pngId != null && !pngId.equals(id)) {
                img = readTexture(pngId, ExportRuntimeConfig.isAnimationEnabled());
            }
        }
        if (img == null) {
            img = cropFromAtlas(sprite);
        }
        return img;
    }

    private static BufferedImage cropFromAtlas(Sprite sprite) {
        Identifier atlasId = sprite.getAtlasId();
        if (atlasId == null) {
            return null;
        }
        BufferedImage atlas = readTexture(atlasId, true);
        if (atlas == null) {
            return null;
        }
        int x = sprite.getX();
        int y = sprite.getY();
        int w = sprite.getContents().getWidth();
        int h = sprite.getContents().getHeight();
        if (x < 0 || y < 0 || w <= 0 || h <= 0) {
            return null;
        }
        int maxX = x + w;
        int maxY = y + h;
        if (maxX > atlas.getWidth() || maxY > atlas.getHeight()) {
            return null;
        }
        try {
            return atlas.getSubimage(x, y, w, h);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                "[TextureLoader][WARN] Failed to crop sprite from atlas " + atlasId + ": " + e.getMessage());
            return null;
        }
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

    public static BufferedImage fromNativeImage(NativeImage nativeImg) {
        return nativeImg == null ? null : nativeImageToBufferedImage(nativeImg);
    }

    /**
     * Extracts the first animation frame by taking the top-most square slice.
     */
    private static BufferedImage extractFirstFrame(BufferedImage img) {
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

    /**
     * Applies a multiplicative tint to every pixel of the image.
     */
    public static BufferedImage tintImage(BufferedImage src, float r, float g, float b) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);

        java.util.stream.IntStream.range(0, pixels.length).parallel().forEach(i -> {
            int argb = pixels[i];
            int a = (argb >>> 24) & 0xFF;
            int rr = Math.min(255, (int) (((argb >>> 16) & 0xFF) * r));
            int gg = Math.min(255, (int) (((argb >>> 8) & 0xFF) * g));
            int bb = Math.min(255, (int) ((argb & 0xFF) * b));
            pixels[i] = (a << 24) | (rr << 16) | (gg << 8) | bb;
        });

        dst.setRGB(0, 0, w, h, pixels, 0, w);
        return dst;
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

    private static String ensurePngExtension(String path) {
        return path.endsWith(".png") ? path : path + ".png";
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
