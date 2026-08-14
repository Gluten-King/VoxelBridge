package com.voxelbridge.export.texture;

import com.voxelbridge.core.texture.AnimatedFrameSet;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/** Pure image policies shared by block, entity, atlas, and animation exporters. */
public final class PbrImages {
    public static final int DEFAULT_NORMAL_COLOR = 0xFF8080FF;
    public static final int DEFAULT_SPECULAR_COLOR = 0x00000000;

    private PbrImages() {}

    /** Replaces Minecraft's magenta/black missing texture with a semantic PBR default. */
    public static BufferedImage sanitizeMissingTexture(BufferedImage image, int defaultColor) {
        if (image == null || !isMissingTexture(image)) return image;
        return solidImage(image.getWidth(), image.getHeight(), defaultColor);
    }

    public static AnimatedFrameSet matchOrDefault(AnimatedFrameSet candidate,
                                                   AnimatedFrameSet baseFrames,
                                                   int defaultColor) {
        if (baseFrames == null || baseFrames.isEmpty()) return candidate;
        if (candidate == null || candidate.isEmpty() || candidate.frames().get(0) == null) {
            return solidFrames(baseFrames, defaultColor);
        }
        BufferedImage first = candidate.frames().get(0);
        return sanitizeMissingTexture(first, defaultColor) == first
            ? candidate
            : solidFrames(baseFrames, defaultColor);
    }

    public static AnimatedFrameSet solidFrames(AnimatedFrameSet baseFrames, int argb) {
        if (baseFrames == null || baseFrames.isEmpty() || baseFrames.frames().get(0) == null) return null;
        BufferedImage base = baseFrames.frames().get(0);
        return new AnimatedFrameSet(List.of(solidImage(base.getWidth(), base.getHeight(), argb)), 1);
    }

    private static BufferedImage solidImage(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        Arrays.fill(row, argb);
        for (int y = 0; y < height; y++) {
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    static boolean isMissingTexture(BufferedImage image) {
        final int tolerance = 16;
        boolean hasMagenta = false;
        boolean hasBlack = false;
        boolean sawAny = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y);
                int alpha = (color >>> 24) & 0xFF;
                if (alpha < 8) continue;
                sawAny = true;
                int red = (color >>> 16) & 0xFF;
                int green = (color >>> 8) & 0xFF;
                int blue = color & 0xFF;
                boolean magenta = Math.abs(red - 255) <= tolerance
                    && green <= tolerance && Math.abs(blue - 255) <= tolerance;
                boolean black = red <= tolerance && green <= tolerance && blue <= tolerance;
                if (magenta) hasMagenta = true;
                else if (black) hasBlack = true;
                else return false;
            }
        }
        return sawAny && (hasMagenta || hasBlack);
    }
}
