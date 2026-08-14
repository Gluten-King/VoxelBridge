package com.voxelbridge.core.texture;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Pure image algorithm for applying Minecraft-style animation metadata to a texture strip. */
public final class AnimationFrameSplitter {
    private AnimationFrameSplitter() {}

    public static AnimatedFrameSet split(BufferedImage image, AnimationMetadata metadata) {
        if (image == null || metadata == null) return null;

        int frameWidth = metadata.frameWidth();
        int frameHeight = metadata.frameHeight();
        if (frameWidth <= 0 && frameHeight <= 0) {
            frameWidth = image.getWidth();
            frameHeight = image.getHeight();
        } else if (frameWidth <= 0) {
            frameWidth = image.getWidth();
        } else if (frameHeight <= 0) {
            frameHeight = image.getHeight();
        }

        int columns = frameWidth > 0 ? image.getWidth() / frameWidth : 0;
        int rows = frameHeight > 0 ? image.getHeight() / frameHeight : 0;
        int frameCount = frameWidth > 0 && frameHeight > 0 ? columns * rows : 0;

        if (frameCount <= 1 && frameWidth > 0
                && image.getHeight() > frameWidth && image.getHeight() % frameWidth == 0) {
            frameHeight = frameWidth;
            columns = image.getWidth() / frameWidth;
            rows = image.getHeight() / frameHeight;
            frameCount = columns * rows;
        }
        if (frameCount <= 1 && frameHeight > 0
                && image.getWidth() > frameHeight && image.getWidth() % frameHeight == 0) {
            frameWidth = frameHeight;
            columns = image.getWidth() / frameWidth;
            rows = image.getHeight() / frameHeight;
            frameCount = columns * rows;
        }
        if (frameWidth <= 0 || frameHeight <= 0
                || image.getWidth() % frameWidth != 0 || image.getHeight() % frameHeight != 0
                || frameCount <= 1) {
            return null;
        }

        List<Integer> order = new ArrayList<>();
        List<AnimationMetadata.FrameTiming> timings = new ArrayList<>();
        for (AnimationMetadata.FrameTiming timing : metadata.frameTimings()) {
            if (timing.index() >= 0 && timing.index() < frameCount) {
                order.add(timing.index());
                timings.add(timing);
            }
        }
        if (order.isEmpty()) {
            for (int i = 0; i < frameCount; i++) order.add(i);
        }

        List<BufferedImage> frames = new ArrayList<>(order.size());
        for (int index : order) {
            int sourceX = (index % columns) * frameWidth;
            int sourceY = (index / columns) * frameHeight;
            BufferedImage frame = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = image.getRGB(sourceX, sourceY, frameWidth, frameHeight, null, 0, frameWidth);
            frame.setRGB(0, 0, frameWidth, frameHeight, pixels, 0, frameWidth);
            frames.add(frame);
        }
        if (frames.isEmpty()) return null;

        AnimationMetadata normalized = new AnimationMetadata(
            metadata.defaultFrameTime(), timings, metadata.interpolate(), frameWidth, frameHeight);
        return new AnimatedFrameSet(frames, normalized);
    }
}
