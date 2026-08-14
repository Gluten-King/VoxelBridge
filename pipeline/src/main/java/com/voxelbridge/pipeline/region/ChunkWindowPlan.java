package com.voxelbridge.pipeline.region;

import com.voxelbridge.pipeline.contract.Region3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable chunk/window plan computed without touching the Minecraft world. */
public final class ChunkWindowPlan {
    private final Region3i region;
    private final List<ChunkWorkUnit> units;

    private ChunkWindowPlan(Region3i region, List<ChunkWorkUnit> units) {
        this.region = region;
        this.units = Collections.unmodifiableList(units);
    }

    public static ChunkWindowPlan create(Region3i region) {
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        List<ChunkWorkUnit> units = new ArrayList<>(
            (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                units.add(new ChunkWorkUnit(
                    new ChunkPos2i(chunkX, chunkZ),
                    Math.max(region.min().x(), chunkX << 4),
                    Math.min(region.max().x(), (chunkX << 4) + 15),
                    Math.max(region.min().z(), chunkZ << 4),
                    Math.min(region.max().z(), (chunkZ << 4) + 15)
                ));
            }
        }
        return new ChunkWindowPlan(region, units);
    }

    public Region3i region() {
        return region;
    }

    public List<ChunkWorkUnit> units() {
        return units;
    }

    public int workerCount(int requestedThreads, int availableProcessors) {
        int maxWorkers = Math.max(1, availableProcessors - 2);
        return Math.max(1, Math.min(Math.min(requestedThreads, units.size()), maxWorkers));
    }
}
