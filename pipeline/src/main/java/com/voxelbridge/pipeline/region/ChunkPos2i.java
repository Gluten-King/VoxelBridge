package com.voxelbridge.pipeline.region;

/** Version-neutral chunk coordinate. */
public record ChunkPos2i(int x, int z) {
    public long packed() {
        return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
    }
}
