package com.voxelbridge.export.texture;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * Holds pre-split animation frames for a sprite.
 * Frame duration defaults to uniform ticks (mc-style); consumers may reinterpret.
 */
public final class AnimatedFrameSet {
    private final List<BufferedImage> frames;
    private final int defaultFrameTime;

    public AnimatedFrameSet(List<BufferedImage> frames, int defaultFrameTime) {
        this.frames = frames == null ? List.of() : List.copyOf(frames);
        this.defaultFrameTime = Math.max(1, defaultFrameTime);
    }

    public List<BufferedImage> frames() {
        return Collections.unmodifiableList(frames);
    }

    public int defaultFrameTime() {
        return defaultFrameTime;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }
}
