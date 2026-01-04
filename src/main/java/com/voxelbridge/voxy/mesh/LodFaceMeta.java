package com.voxelbridge.voxy.mesh;

/**
 * Per-face metadata used for LOD baking/meshing.
 */
public final class LodFaceMeta {
    public final int minX;
    public final int maxX;
    public final int minY;
    public final int maxY;
    public final float depth;
    public final boolean empty;
    public final boolean hasBakedTint;

    public LodFaceMeta(int minX, int maxX, int minY, int maxY, float depth, boolean empty) {
        this(minX, maxX, minY, maxY, depth, empty, false);
    }

    public LodFaceMeta(int minX, int maxX, int minY, int maxY, float depth, boolean empty, boolean hasBakedTint) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.depth = depth;
        this.empty = empty;
        this.hasBakedTint = hasBakedTint;
    }
}
