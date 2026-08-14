package com.voxelbridge.pipeline.contract;

/** Integer world position without a dependency on Minecraft's BlockPos. */
public record BlockPos3i(int x, int y, int z) {
    public BlockPos3i offset(Face face) {
        return new BlockPos3i(x + face.stepX(), y + face.stepY(), z + face.stepZ());
    }

    public long packed() {
        return ((long) (x & 0x3FFFFFF) << 38)
            | ((long) (z & 0x3FFFFFF) << 12)
            | (long) (y & 0xFFF);
    }
}
