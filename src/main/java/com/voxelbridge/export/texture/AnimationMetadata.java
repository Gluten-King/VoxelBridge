package com.voxelbridge.export.texture;

import java.util.Collections;
import java.util.List;

/**
 * Stores complete animation metadata extracted from .mcmeta files.
 * Supports all Minecraft animation properties including per-frame timing,
 * custom frame order, and interpolation settings.
 */
public record AnimationMetadata(int defaultFrameTime, List<FrameTiming> frameTimings, boolean interpolate,
                                int frameWidth, int frameHeight) {
    /**
     * Simple constructor for uniform timing (backward compatibility)
     */
    public AnimationMetadata(int defaultFrameTime) {
        this(defaultFrameTime, List.of(), false, 0, 0);
    }

    /**
     * Full constructor for metadata-driven animations
     */
    public AnimationMetadata(int defaultFrameTime,
                             List<FrameTiming> frameTimings,
                             boolean interpolate,
                             int frameWidth,
                             int frameHeight) {
        this.defaultFrameTime = Math.max(1, defaultFrameTime);
        this.frameTimings = frameTimings == null ? List.of() : List.copyOf(frameTimings);
        this.interpolate = interpolate;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    @Override
    public List<FrameTiming> frameTimings() {
        return Collections.unmodifiableList(frameTimings);
    }

    /**
     * Get timing for a specific frame index
     */
    public int getFrameTime(int frameIndex) {
        if (frameIndex >= 0 && frameIndex < frameTimings.size()) {
            return frameTimings.get(frameIndex).time();
        }
        return defaultFrameTime;
    }

    /**
     * Check if custom frame order is specified
     */
    public boolean hasCustomFrameOrder() {
        return !frameTimings.isEmpty();
    }

    /**
     * Record for per-frame timing information
     */
    public record FrameTiming(int index, int time) {
    }
}
