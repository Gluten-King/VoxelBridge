package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.util.ExportLogger;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits animation strips (vertical or horizontal) into per-frame images and caches them.
 */
public final class AnimatedTextureHelper {

    private AnimatedTextureHelper() {}

    public static AnimatedFrameSet extractAndStore(String spriteKey, BufferedImage img, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || spriteKey == null || img == null || repo == null) {
            return null;
        }
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }

        SplitResult split = split(img);
        if (split == null || split.frames.size() < 2) {
            return null;
        }

        AnimatedFrameSet set = new AnimatedFrameSet(split.frames, 1);
        repo.putAnimation(spriteKey, set);
        ExportLogger.log(String.format("[Animation] Detected strip %s -> %d frames (%dx%d each)",
            spriteKey, split.frames.size(), split.frameSize, split.frameSize));
        return set;
    }

    private static SplitResult split(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean vertical = h > w && h % w == 0;
        boolean horizontal = w > h && w % h == 0;
        if (!vertical && !horizontal) {
            return null;
        }
        int frameSize = vertical ? w : h;
        int frameCount = vertical ? h / frameSize : w / frameSize;
        if (frameCount < 2 || frameSize <= 0) {
            return null;
        }

        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int x = horizontal ? i * frameSize : 0;
            int y = vertical ? i * frameSize : 0;
            try {
                BufferedImage frame = new BufferedImage(frameSize, frameSize, BufferedImage.TYPE_INT_ARGB);
                for (int yy = 0; yy < frameSize; yy++) {
                    for (int xx = 0; xx < frameSize; xx++) {
                        int argb = img.getRGB(x + xx, y + yy);
                        frame.setRGB(xx, yy, argb);
                    }
                }
                frames.add(frame);
            } catch (Exception e) {
                ExportLogger.log("[Animation][WARN] Failed to split frame " + i + ": " + e.getMessage());
            }
        }
        return new SplitResult(frameSize, frames);
    }

    private record SplitResult(int frameSize, List<BufferedImage> frames) {}
}
