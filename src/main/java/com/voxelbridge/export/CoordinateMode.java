package com.voxelbridge.export;

/**
 * Coordinate system mode for exported models.
 */
public enum CoordinateMode {
    /**
     * Center the model at origin (0,0,0) by subtracting region center.
     * Default mode - easier to work with in 3D software.
     */
    CENTERED,

    /**
     * Keep original world coordinates.
     * Useful for preserving exact positions relative to world origin.
     */
    WORLD_ORIGIN
}
