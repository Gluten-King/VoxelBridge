package com.voxelbridge.pipeline.contract;

/** Inclusive integer export region. */
public record Region3i(BlockPos3i min, BlockPos3i max) {
    public Region3i {
        if (min == null || max == null) {
            throw new IllegalArgumentException("Region bounds are required");
        }
        BlockPos3i normalizedMin = new BlockPos3i(
            Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        BlockPos3i normalizedMax = new BlockPos3i(
            Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = normalizedMin;
        max = normalizedMax;
    }

    public boolean contains(BlockPos3i position) {
        return position.x() >= min.x() && position.x() <= max.x()
            && position.y() >= min.y() && position.y() <= max.y()
            && position.z() >= min.z() && position.z() <= max.z();
    }
}
