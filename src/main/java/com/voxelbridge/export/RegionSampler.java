package com.voxelbridge.export;

import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.ProgressNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Format-agnostic region sampler that collects geometry from a Minecraft region
 * and feeds it into any SceneSink implementation.
 *
 * This class is responsible ONLY for:
 * - Iterating through chunks and blocks
 * - Delegating block/fluid sampling to specialized samplers
 * - Progress tracking and logging
 *
 * It does NOT know about glTF, OBJ, or any specific output format.
 */
@OnlyIn(Dist.CLIENT)
public final class RegionSampler {

    private RegionSampler() {}

    /**
     * Samples all geometry in the given region and feeds it to the provided sink.
     *
     * @param level The world level
     * @param pos1 First corner of the region
     * @param pos2 Second corner of the region
     * @param sink The scene sink that will receive geometry data
     * @param ctx Export context for shared state
     */
    public static void sampleRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    SceneSink sink,
                                    ExportContext ctx) {
        ExportLogger.log("[RegionSampler] Starting region sampling");

        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[RegionSampler] Must run on client side!");
        }

        // Calculate region bounds
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        ExportLogger.log(String.format("[RegionSampler] Bounds: X[%d to %d], Y[%d to %d], Z[%d to %d]",
                minX, maxX, minY, maxY, minZ, maxZ));

        // Calculate chunk bounds
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        // Create sampler for blocks/fluids
        BlockExporter blockSampler = new BlockExporter(ctx, sink, level);

        // Set region bounds for occlusion culling (boundary blocks won't be culled)
        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);
        blockSampler.setRegionBounds(regionMin, regionMax);

        var chunkCache = clientLevel.getChunkSource();
        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        int maxWorkerCount = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), totalChunks));

        AtomicInteger processedChunks = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("VoxelBridge-Sampler-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        // Collect all chunk coords inside selection so we know the total for progress reporting
        List<int[]> chunkBounds = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // Calculate block bounds within this chunk
                int cminX = Math.max(minX, cx << 4);
                int cmaxX = Math.min(maxX, (cx << 4) + 15);
                int cminZ = Math.max(minZ, cz << 4);
                int cmaxZ = Math.min(maxZ, (cz << 4) + 15);

                chunkBounds.add(new int[]{cx, cz, cminX, cmaxX, cminZ, cmaxZ});
            }
        }

        int scheduledChunks = chunkBounds.size();
        if (scheduledChunks == 0) {
            ExportLogger.log("[RegionSampler] No chunks to process after filtering");
            return;
        }

        Set<Long> chunkKeys = chunkBounds.stream()
            .map(b -> new ChunkPos(b[0], b[1]).toLong())
            .collect(Collectors.toSet());
        ExportProgressTracker.initForExport(chunkKeys);

        int workerCount = Math.max(1, Math.min(maxWorkerCount, scheduledChunks));
        ExportLogger.log(String.format("[RegionSampler] Using %d worker threads for %d chunks",
            workerCount, scheduledChunks));

        ExecutorService executor = Executors.newFixedThreadPool(workerCount, factory);
        Minecraft mc = Minecraft.getInstance();

        // Submit chunk workers
        for (int[] bounds : chunkBounds) {
            final int chunkX = bounds[0];
            final int chunkZ = bounds[1];
            final int fx0 = bounds[2];
            final int fx1 = bounds[3];
            final int fz0 = bounds[4];
            final int fz1 = bounds[5];
            futures.add(executor.submit(() -> {
                ExportProgressTracker.markRunning(chunkX, chunkZ);
                LevelChunk chunk = chunkCache.getChunk(chunkX, chunkZ, false);
                if (chunk == null || chunk.isEmpty()) {
                    ExportLogger.log(String.format("[RegionSampler] Chunk (%d, %d) is null or empty", chunkX, chunkZ));
                    ExportProgressTracker.markDone(chunkX, chunkZ);
                    int current = processedChunks.incrementAndGet();
                    double percent = 100.0 * current / scheduledChunks;
                    if (current % 10 == 0 || current == scheduledChunks) {
                        ExportLogger.log(String.format("[RegionSampler] Progress: %d/%d chunks (%.1f%%)",
                            current, scheduledChunks, percent));
                    }
                    ProgressNotifier.show(mc, percent, current, scheduledChunks);
                    return;
                }

                for (int x = fx0; x <= fx1; x++) {
                    for (int z = fz0; z <= fz1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);

                            try {
                                blockSampler.sampleBlock(state, pos);
                            } catch (Throwable t) {
                                ExportLogger.log(String.format(
                                    "[RegionSampler][ERROR] Failed to sample block at %s: %s",
                                    pos, t.getMessage()));
                                t.printStackTrace();
                            }
                        }
                    }
                }

                ExportProgressTracker.markDone(chunkX, chunkZ);
                int current = processedChunks.incrementAndGet();
                double percent = 100.0 * current / scheduledChunks;
                if (current % 10 == 0 || current == scheduledChunks) {
                    ExportLogger.log(String.format("[RegionSampler] Progress: %d/%d chunks (%.1f%%)",
                        current, scheduledChunks, percent));
                }
                ProgressNotifier.show(mc, percent, current, scheduledChunks);
            }));
        }

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            ExportLogger.log("[RegionSampler][ERROR] Worker failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }

        ExportLogger.log(String.format("[RegionSampler] Sampling complete - processed %d/%d chunks",
            processedChunks.get(), scheduledChunks));
    }
}
