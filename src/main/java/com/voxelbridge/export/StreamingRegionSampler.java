package com.voxelbridge.export;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.exporter.BlockExporter;
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

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming region sampler that continuously monitors loaded chunks
 * and exports them as soon as they become available.
 */
@OnlyIn(Dist.CLIENT)
public final class StreamingRegionSampler {

    private StreamingRegionSampler() {}

    /**
     * Samples region with streaming approach - exports chunks as they load.
     */
    public static void sampleRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    SceneSink sink,
                                    ExportContext ctx) {
        ExportLogger.log("[StreamingRegionSampler] Starting streaming export");

        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[StreamingRegionSampler] Must run on client side!");
        }

        // Calculate region bounds
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        ExportLogger.log(String.format("[StreamingRegionSampler] Bounds: X[%d to %d], Y[%d to %d], Z[%d to %d]",
                minX, maxX, minY, maxY, minZ, maxZ));

        // Calculate chunk bounds
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        // Initialize all chunks in selection as PENDING
        Set<ChunkPos> allChunks = ConcurrentHashMap.newKeySet();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                allChunks.add(new ChunkPos(cx, cz));
            }
        }

        Set<Long> chunkKeys = allChunks.stream()
            .map(ChunkPos::toLong)
            .collect(java.util.stream.Collectors.toSet());
        ExportProgressTracker.initForExport(chunkKeys);

        int totalChunks = allChunks.size();
        ExportLogger.log(String.format("[StreamingRegionSampler] Total chunks in selection: %d", totalChunks));

        // Create block sampler
        BlockExporter blockSampler = new BlockExporter(ctx, sink, level);
        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);
        blockSampler.setRegionBounds(regionMin, regionMax);

        var chunkCache = clientLevel.getChunkSource();
        Minecraft mc = Minecraft.getInstance();

        // Thread pool configuration
        int threadCount = ExportRuntimeConfig.getExportThreadCount();
        int workerCount = Math.max(1, Math.min(threadCount, totalChunks));
        ExportLogger.log(String.format("[StreamingRegionSampler] Using %d worker threads", workerCount));

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("VoxelBridge-Streaming-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(workerCount, factory);
        Set<ChunkPos> processing = ConcurrentHashMap.newKeySet();
        AtomicBoolean keepRunning = new AtomicBoolean(true);
        AtomicInteger scanCycles = new AtomicInteger(0);

        // Streaming monitor thread
        Thread monitor = new Thread(() -> {
            try {
                while (keepRunning.get()) {
                    ExportProgressTracker.Progress progress = ExportProgressTracker.progress();

                    // Check if complete
                    if (progress.isComplete()) {
                        ExportLogger.log("[StreamingRegionSampler] All chunks processed, stopping monitor");
                        break;
                    }

                    // Scan for newly loaded chunks
                    int submitted = 0;
                    for (ChunkPos chunkPos : allChunks) {
                        // Skip if already processing or completed
                        if (processing.contains(chunkPos)) {
                            continue;
                        }

                        long key = chunkPos.toLong();
                        ExportProgressTracker.ChunkState state = ExportProgressTracker.snapshot().get(key);

                        // Only process PENDING or RETRY chunks
                        if (state != ExportProgressTracker.ChunkState.PENDING &&
                            state != ExportProgressTracker.ChunkState.RETRY) {
                            continue;
                        }

                        // Check if chunk is loaded
                        LevelChunk chunk = chunkCache.getChunk(chunkPos.x, chunkPos.z, false);
                        if (chunk != null && !chunk.isEmpty()) {
                            processing.add(chunkPos);

                            // Calculate block bounds for this chunk
                            int cminX = Math.max(minX, chunkPos.x << 4);
                            int cmaxX = Math.min(maxX, (chunkPos.x << 4) + 15);
                            int cminZ = Math.max(minZ, chunkPos.z << 4);
                            int cmaxZ = Math.min(maxZ, (chunkPos.z << 4) + 15);

                            // Submit export task
                            executor.submit(() -> exportChunk(
                                chunk, chunkPos, level, blockSampler,
                                cminX, cmaxX, cminZ, cmaxZ, minY, maxY,
                                mc, processing
                            ));

                            submitted++;
                        }
                    }

                    // Log scan cycle info
                    int cycle = scanCycles.incrementAndGet();
                    if (submitted > 0 || cycle % 10 == 0) {
                        ExportLogger.log(String.format(
                            "[StreamingRegionSampler] Scan #%d: submitted %d chunks | Progress: %d done, %d retry, %d failed, %d pending",
                            cycle, submitted, progress.done(), progress.retrying(), progress.failed(), progress.pending()
                        ));
                    }

                    // Show progress update
                    if (submitted > 0 || cycle % 5 == 0) {
                        ProgressNotifier.showDetailed(mc, progress);
                    }

                    // Sleep before next scan
                    Thread.sleep(200); // Scan every 200ms
                }
            } catch (InterruptedException e) {
                ExportLogger.log("[StreamingRegionSampler] Monitor interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                ExportLogger.log("[StreamingRegionSampler][ERROR] Monitor failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "VoxelBridge-Monitor");

        monitor.setDaemon(true);
        monitor.start();

        // Wait for completion or timeout
        try {
            long startTime = System.currentTimeMillis();
            long timeout = 600_000; // 10 minutes maximum

            while (!ExportProgressTracker.progress().isComplete()) {
                Thread.sleep(1000);

                // Check timeout
                if (System.currentTimeMillis() - startTime > timeout) {
                    ExportLogger.log("[StreamingRegionSampler][WARN] Export timeout reached");
                    break;
                }
            }

            keepRunning.set(false);
            monitor.interrupt();
            monitor.join(2000);

        } catch (InterruptedException e) {
            ExportLogger.log("[StreamingRegionSampler] Main thread interrupted");
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Final report
        ExportProgressTracker.Progress finalProgress = ExportProgressTracker.progress();
        String summary = String.format(
            "[StreamingRegionSampler] Export complete - Done: %d, Failed: %d, Retry needed: %d, Total: %d",
            finalProgress.done(), finalProgress.failed(), finalProgress.retrying(), finalProgress.total()
        );
        ExportLogger.log(summary);

        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(summary), false
                );
            }
        });
    }

    private static void exportChunk(LevelChunk chunk, ChunkPos chunkPos, Level level,
                                   BlockExporter blockSampler,
                                   int minX, int maxX, int minZ, int maxZ,
                                   int minY, int maxY,
                                   Minecraft mc, Set<ChunkPos> processing) {
        try {
            ExportProgressTracker.markRunning(chunkPos.x, chunkPos.z);

            // Validate chunk is still loaded before starting
            if (chunk.isEmpty()) {
                ExportLogger.log(String.format("[StreamingRegionSampler] Chunk (%d, %d) became empty before export",
                    chunkPos.x, chunkPos.z));
                ExportProgressTracker.markRetry(chunkPos.x, chunkPos.z);
                return;
            }

            int blockCount = 0;

            // Export all blocks in chunk bounds
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        // Periodic validation - check chunk still valid every 16 blocks
                        if (blockCount % 16 == 0 && chunk.isEmpty()) {
                            ExportLogger.log(String.format(
                                "[StreamingRegionSampler] Chunk (%d, %d) unloaded during export at block %d",
                                chunkPos.x, chunkPos.z, blockCount
                            ));
                            ExportProgressTracker.markRetry(chunkPos.x, chunkPos.z);
                            return;
                        }

                        BlockPos pos = new BlockPos(x, y, z);

                        // Use chunk.getBlockState instead of level.getBlockState for safety
                        BlockState state = chunk.getBlockState(pos);

                        try {
                            blockSampler.sampleBlock(state, pos);
                            blockCount++;
                        } catch (Throwable t) {
                            ExportLogger.log(String.format(
                                "[StreamingRegionSampler][ERROR] Failed to sample block at %s: %s",
                                pos, t.getMessage()
                            ));
                            t.printStackTrace();
                        }
                    }
                }
            }

            // Final validation
            if (chunk.isEmpty()) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) unloaded after export, marking for retry",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markRetry(chunkPos.x, chunkPos.z);
            } else {
                // Successfully exported
                ExportProgressTracker.markDone(chunkPos.x, chunkPos.z);
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) exported successfully (%d blocks)",
                    chunkPos.x, chunkPos.z, blockCount
                ));
            }

        } catch (Exception e) {
            ExportLogger.log(String.format(
                "[StreamingRegionSampler][ERROR] Chunk (%d, %d) export failed: %s",
                chunkPos.x, chunkPos.z, e.getMessage()
            ));
            e.printStackTrace();
            ExportProgressTracker.markFailed(chunkPos.x, chunkPos.z);
        } finally {
            processing.remove(chunkPos);
        }
    }
}
