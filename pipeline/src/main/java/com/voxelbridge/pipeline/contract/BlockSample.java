package com.voxelbridge.pipeline.contract;

/** Stable block facts needed to request geometry from a version runtime. */
public record BlockSample(
    BlockPos3i position,
    ResourceId blockId,
    int lightEmission,
    float randomOffsetX,
    float randomOffsetY,
    float randomOffsetZ
) {
    public BlockSample {
        if (position == null || blockId == null) {
            throw new IllegalArgumentException("Block position and id are required");
        }
        lightEmission = Math.max(0, lightEmission);
    }
}
