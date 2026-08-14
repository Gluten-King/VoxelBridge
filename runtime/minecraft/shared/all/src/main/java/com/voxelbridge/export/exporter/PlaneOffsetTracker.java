package com.voxelbridge.export.exporter;

import net.minecraft.core.Direction;

/**
 * Tracks per-plane quad order and applies a tiny offset to later quads to reduce z-fighting.
 */
public final class PlaneOffsetTracker {
    private final com.voxelbridge.core.util.geometry.PlaneOffsetTrackerCore core;
    private static final float BUCKET_QUANT = 1000f;

    public PlaneOffsetTracker() {
        this.core = new com.voxelbridge.core.util.geometry.PlaneOffsetTrackerCore();
    }

    public PlaneOffsetTracker(float cellSize, float bucketEps, float overlapEps) {
        this.core = new com.voxelbridge.core.util.geometry.PlaneOffsetTrackerCore(cellSize, bucketEps, overlapEps);
    }

    public PlaneOffsetTracker(float cellSize, float bucketEps, float overlapEps, float normalQuant, float distQuant, float posQuant) {
        this.core = new com.voxelbridge.core.util.geometry.PlaneOffsetTrackerCore(cellSize, bucketEps, overlapEps, normalQuant, distQuant, posQuant);
    }

    public void clear() {
        core.clear();
    }

    public void applyOffset(float[] positions, float[] normal) {
        core.applyOffset(positions, normal);
    }

    public void applyOffset(float[] positions, float[] normal, Direction dir) {
        if (dir == null) {
            core.applyOffset(positions, normal);
            return;
        }
        core.applyOffset(positions, normal, dir.getStepX(), dir.getStepY(), dir.getStepZ(), true);
    }

    public void applyOffsetWithBucketKey(float[] positions, float[] normal, Direction dir, long bucketKey) {
        if (dir == null) {
            core.applyOffsetWithBucketKey(positions, normal, 0f, 0f, 0f, false, bucketKey);
            return;
        }
        core.applyOffsetWithBucketKey(positions, normal, dir.getStepX(), dir.getStepY(), dir.getStepZ(), true, bucketKey);
    }

    public static long hashAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int qminX = Math.round(minX * BUCKET_QUANT);
        int qminY = Math.round(minY * BUCKET_QUANT);
        int qminZ = Math.round(minZ * BUCKET_QUANT);
        int qmaxX = Math.round(maxX * BUCKET_QUANT);
        int qmaxY = Math.round(maxY * BUCKET_QUANT);
        int qmaxZ = Math.round(maxZ * BUCKET_QUANT);
        long h1 = hash4(qminX, qminY, qminZ, qmaxX);
        long h2 = hash4(qmaxY, qmaxZ, 0, 0);
        return h1 ^ (h2 * 31);
    }

    private static long hash4(int a, int b, int c, int d) {
        long h = 1469598103934665603L;
        h = (h ^ a) * 1099511628211L;
        h = (h ^ b) * 1099511628211L;
        h = (h ^ c) * 1099511628211L;
        h = (h ^ d) * 1099511628211L;
        return h;
    }
}
