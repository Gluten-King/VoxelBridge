package com.voxelbridge.platform.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.compat.NativeImageCompat;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * TextureLoader reads vanilla or resource-pack textures and exposes tint helpers.
 */
public final class TextureLoader {

    private TextureLoader() {}

    /**
     * Loads a PNG texture with optional preservation of animation strips.
     */
    public static BufferedImage readTexture(ResourceLocation png, boolean preserveAnimationStrip) {
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
                
                // Try to read dynamic texture via platform helper
                var dynamicOpt = com.voxelbridge.adapter.Adapters.getTextureHelper().readTexture(png);
                
                if (dynamicOpt.isPresent()) {
                    NativeImage nativeDynamic = dynamicOpt.get();
                    try {
                        BufferedImage dynamic = nativeImageToBufferedImage(nativeDynamic);
                        if (com.voxelbridge.export.texture.DynamicTextureUtil.normalizeAll(png).kind()
                                == com.voxelbridge.export.texture.DynamicTextureUtil.DynamicTextureKind.GLYPH) {
                            com.voxelbridge.export.texture.DynamicTextureUtil.cleanGlyphPixels(dynamic);
                        }
                        if (logResolve) {
                            VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format("[TextureLoader] Loaded dynamic texture %s (%dx%d)", png, dynamic.getWidth(), dynamic.getHeight()));
                        }
                        return preserveAnimationStrip ? dynamic : extractFirstFrame(dynamic);
                    } finally {
                        nativeDynamic.close();
                    }
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

    public static BufferedImage fromSprite(TextureAtlasSprite sprite, boolean preserveAnimationStrip) {
        if (sprite == null) {
            return null;
        }
        NativeImage nativeImg = com.voxelbridge.compat.SpriteCompat.getOriginalImage(sprite);
        if (nativeImg == null) {
            return null;
        }
        BufferedImage img = nativeImageToBufferedImage(nativeImg);

        // Extract first frame for animated textures
        if (preserveAnimationStrip) {
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
                int c = NativeImageCompat.getPixelRgba(nativeImg, x, y);
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
     * Converts an ARGB integer color into RGB multipliers in the range [0, 1].
     */
    public static float[] rgbMul(int rgb) {
        return com.voxelbridge.core.util.color.ColorUtil.rgbMul(rgb);
    }
}





