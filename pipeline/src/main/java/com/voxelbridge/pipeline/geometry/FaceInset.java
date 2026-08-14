package com.voxelbridge.pipeline.geometry;

import com.voxelbridge.pipeline.contract.Face;

/** Applies a small inward displacement to a borrowed XYZ position buffer. */
public final class FaceInset {
    private FaceInset() {}

    public static void apply(float[] positions, Face face, float distance) {
        if (positions == null || positions.length < 3 || face == null || face == Face.NONE) return;
        float amount = Math.max(0f, distance);
        float dx = -face.stepX() * amount;
        float dy = -face.stepY() * amount;
        float dz = -face.stepZ() * amount;
        for (int offset = 0; offset + 2 < positions.length; offset += 3) {
            positions[offset] += dx;
            positions[offset + 1] += dy;
            positions[offset + 2] += dz;
        }
    }
}
