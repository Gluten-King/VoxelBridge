package com.voxelbridge.export.texture;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import com.voxelbridge.config.ExportRuntimeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * TextureLoader reads vanilla or resource-pack textures and exposes tint helpers.
 */
@OnlyIn(Dist.CLIENT)
public final class TextureLoader {

    private TextureLoader() {}

    /**
     * Loads a PNG texture, honoring resource-pack overrides and returning only the first animation frame.
     */
    public static BufferedImage readTexture(ResourceLocation png) {
        return readTexture(png, ExportRuntimeConfig.isAnimationEnabled());
    }

    /**
     * Loads a PNG texture with optional preservation of animation strips.
     */
    public static BufferedImage readTexture(ResourceLocation png, boolean preserveAnimationStrip) {
        boolean logResolve = VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE);
        if (logResolve) {
            VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Resolving %s", png));
        }
        try {
            var rm = Minecraft.getInstance().getResourceManager();
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
            try (InputStream in = opt.get().open()) {
                BufferedImage img = readPngNoColorConversion(in);
                if (logResolve) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Loaded %s (%dx%d)", png, img.getWidth(), img.getHeight()));
                }
                if (preserveAnimationStrip) {
                    return img;
                }
                BufferedImage firstFrame = extractFirstFrame(img);
                if (firstFrame != img) {
                    if (logResolve) {
                        VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Extracted first frame for %s -> %dx%d",
                                png, firstFrame.getWidth(), firstFrame.getHeight()));
                    }
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

    public static BufferedImage fromNativeImage(NativeImage nativeImg) {
        return nativeImageToBufferedImage(nativeImg);
    }

    public static BufferedImage fromSprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return null;
        }
        NativeImage nativeImg = sprite.contents().getOriginalImage();
        BufferedImage img = nativeImageToBufferedImage(nativeImg);

        // Extract first frame for animated textures
        if (ExportRuntimeConfig.isAnimationEnabled()) {
            return img;
        }
        BufferedImage firstFrame = extractFirstFrame(img);
        if (firstFrame != img) {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Extracted first frame from sprite %s -> %dx%d",
                        sprite.contents().name(), firstFrame.getWidth(), firstFrame.getHeight()));
            }
        }
        return firstFrame;
    }

    private static BufferedImage nativeImageToBufferedImage(NativeImage nativeImg) {
        int w = nativeImg.getWidth();
        int h = nativeImg.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] allPixels = new int[w * h];

        // Process sequentially to ensure thread safety with NativeImage
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int c = nativeImg.getPixelRGBA(x, y);
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
     * Resizes a texture to 16x16 using nearest-neighbor scaling.
     */
    private static BufferedImage resizeToTile(BufferedImage src) {
        if (src.getWidth() == 16 && src.getHeight() == 16) {
            return src;
        }

        BufferedImage resized = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = resized.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, 16, 16, null);
        g.dispose();
        return resized;
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
    public static ResourceLocation spriteKeyToTexturePNG(String spriteKey) {
        // Normalize/sanitize first to ensure a valid ResourceLocation layout.
        spriteKey = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(spriteKey);

        int separator = spriteKey.indexOf(':');
        String namespace = separator > 0 ? spriteKey.substring(0, separator) : "minecraft";
        String rawPath = separator > 0 ? spriteKey.substring(separator + 1) : spriteKey;

        // Handle blockentity: and entity: prefixes specially
        if ("blockentity".equals(namespace) || "entity".equals(namespace)) {
            // rawPath is like "minecraft/entity/chest/normal" or "minecraft/textures/atlas/signs.png"
            // Extract the actual namespace and path, tolerating ":" inside the path
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
            return ResourceLocation.fromNamespaceAndPath(actualNamespace, actualPath);
        }

        String normalizedPath = normalizeSpritePath(rawPath);
        normalizedPath = sanitizePath(normalizedPath);
        String safeNamespace = sanitizePath(namespace);
        return ResourceLocation.fromNamespaceAndPath(safeNamespace, normalizedPath);
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





