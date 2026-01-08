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
 * 对齐 NeoForge 1.21.8 的读取逻辑，依赖 ResourceManager + DynamicTextureReader。
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
        String path = png != null ? png.getPath() : "";
        boolean isPlayerDynamic =
            path.startsWith("skins/") || path.startsWith("skin/")
                || path.startsWith("capes/") || path.startsWith("cape/")
                || path.startsWith("textures/entity/player/")
                || path.startsWith("textures/entity/elytra/");
        boolean isDynamic = isPlayerDynamic || path.startsWith("custom/");

        // 玩家/自定义等动态纹理强制走 GPU/TextureManager。
        if (isDynamic) {
            BufferedImage dyn = DynamicTextureReader.tryRead(png, preserveAnimationStrip);
            if (dyn != null) {
                return dyn;
            }
            if (logResolve) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                    String.format("[TextureLoader][WARN] GPU read failed for %s", png));
            }
            return null;
        }

        // 1) 先尝试资源文件（方块/静态纹理）。
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            var opt = rm.getResource(png);
            if (opt.isPresent()) {
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
            }
        } catch (Throwable ignored) {
        }

        // 2) 动态/GPU 兜底。
        BufferedImage dynamic = DynamicTextureReader.tryRead(png, preserveAnimationStrip);
        if (dynamic != null) {
            return dynamic;
        }
        if (logResolve) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                String.format("[TextureLoader][WARN] GPU read failed for %s", png));
        }
        return null;
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
        Identifier id = sprite.getContents().getId();
        BufferedImage img = null;

        // Try direct PNG load first.
        Identifier pngId = ensureSpritePng(id);
        if (pngId != null) {
            img = readTexture(pngId, ExportRuntimeConfig.isAnimationEnabled());
        }

        // Fallback: crop from atlas if direct load failed.
        if (img == null) {
            img = cropFromAtlas(sprite);
        }

        return img;
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

    private static BufferedImage cropFromAtlas(Sprite sprite) {
        try {
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
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (x < 0 || y < 0 || x + w > atlas.getWidth() || y + h > atlas.getHeight()) {
                return null;
            }
            BufferedImage sub = atlas.getSubimage(x, y, w, h);
            if (!ExportRuntimeConfig.isAnimationEnabled()) {
                sub = extractFirstFrame(sub);
            }
            return sub;
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                "[TextureLoader][WARN] Failed to crop sprite from atlas: " + e.getMessage());
            return null;
        }
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
