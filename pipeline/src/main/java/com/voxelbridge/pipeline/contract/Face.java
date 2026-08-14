package com.voxelbridge.pipeline.contract;

/** Canonical face order used by the export pipeline. */
public enum Face {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    NONE(0, 0, 0);

    private final int stepX;
    private final int stepY;
    private final int stepZ;

    Face(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    public int stepX() { return stepX; }
    public int stepY() { return stepY; }
    public int stepZ() { return stepZ; }

    public Face opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
            case NONE -> NONE;
        };
    }
}
