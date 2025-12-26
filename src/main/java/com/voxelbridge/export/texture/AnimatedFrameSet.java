package com.voxelbridge.export.texture;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * Holds pre-split animation frames for a sprite.
 * Frame duration defaults to uniform ticks (mc-style); consumers may reinterpret.
 */
public record AnimatedFrameSet(List<BufferedImage> frames, AnimationMetadata metadata) {
    /**
     * Backward-compatible constructor for simple animations with uniform timing
     */
    public AnimatedFrameSet(List<BufferedImage> frames, int defaultFrameTime) {
        this(frames, new AnimationMetadata(defaultFrameTime));
    }

    /**
     * New constructor with full metadata support
     */
    public AnimatedFrameSet(List<BufferedImage> frames, AnimationMetadata metadata) {
        this.frames = frames == null ? List.of() : List.copyOf(frames);
        this.metadata = metadata != null ? metadata : new AnimationMetadata(1);
    }

    @Override
    public List<BufferedImage> frames() {
        return Collections.unmodifiableList(frames);
    }

    /**
     * @deprecated Use {@link #metadata()}.defaultFrameTime() instead
     */
    @Deprecated
    public int defaultFrameTime() {
        return metadata.defaultFrameTime();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }
}
