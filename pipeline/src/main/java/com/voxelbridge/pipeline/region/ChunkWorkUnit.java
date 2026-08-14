package com.voxelbridge.pipeline.region;

/** Inclusive block bounds belonging to one chunk inside an export region. */
public record ChunkWorkUnit(
    ChunkPos2i chunk,
    int minX,
    int maxX,
    int minZ,
    int maxZ
) {
}
