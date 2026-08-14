package com.voxelbridge.pipeline.geometry;

/** Two-dimensional bounds of a quad projected onto its block face. */
public record FaceBounds(float minA, float maxA, float minB, float maxB) {
    public FaceBounds {
        if (!Float.isFinite(minA) || !Float.isFinite(maxA)
            || !Float.isFinite(minB) || !Float.isFinite(maxB)) {
            throw new IllegalArgumentException("Face bounds must be finite");
        }
        if (minA > maxA || minB > maxB) {
            throw new IllegalArgumentException("Face bounds must be ordered");
        }
    }
}
