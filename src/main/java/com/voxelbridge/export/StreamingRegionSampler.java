package com.voxelbridge.export;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.ProgressNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
                    final ChunkPos playerChunk = mc.player != null ? mc.player.chunkPosition() : null;
                    final int activeDistance = Math.max(0, mc.options != null ? mc.options.getEffectiveRenderDistance() : 0);

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

                        // Only schedule chunks within the current active (visible) distance from player.
                        if (playerChunk != null) {
                            int dist = Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z));
                            if (dist > activeDistance) {
                                if (state == ExportProgressTracker.ChunkState.RETRY) {
                                    ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                                }
                                continue;
                            }
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
                                chunk, chunkPos, level, chunkCache, blockSampler,
                                cminX, cmaxX, cminZ, cmaxZ, minY, maxY,
                                mc, processing, playerChunk, activeDistance
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
                                   ClientChunkCache chunkCache, BlockExporter blockSampler,
                                   int minX, int maxX, int minZ, int maxZ,
                                   int minY, int maxY,
                                   Minecraft mc, Set<ChunkPos> processing,
                                   ChunkPos playerChunk, int activeDistance) {
        try {
            ExportProgressTracker.markRunning(chunkPos.x, chunkPos.z);

            // Validate chunk is still loaded before starting
            if (chunk.isEmpty()) {
                ExportLogger.log(String.format("[StreamingRegionSampler] Chunk (%d, %d) became empty before export",
                    chunkPos.x, chunkPos.z));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            int blockCount = 0;

            // Ensure all surrounding chunks inside the region are loaded before exporting to avoid
            // generating boundary faces when neighbors are still unloaded.
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;
            if (!areNeighborChunksReady(chunkPos, minChunkX, maxChunkX, minChunkZ, maxChunkZ, chunkCache, true, playerChunk, activeDistance)) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) neighbors not ready (full ring), retrying later",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Ensure the chunk has been rendered/compiled (visible) before exporting to avoid
            // generating faces against not-yet-visible neighbors.
            if (!isChunkRenderable(level, chunkPos)) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) not yet render-ready, retrying later",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

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
                            ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
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

            // If any block sampling saw missing neighbor chunks, retry later.
            if (blockSampler.hadMissingNeighborAndReset()) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) skipped due to missing neighbor chunk, retrying later",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Final validation
            if (chunk.isEmpty()) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) unloaded after export, marking for retry",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
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

    private static boolean areNeighborChunksReady(ChunkPos chunkPos,
                                                  int minChunkX, int maxChunkX,
                                                  int minChunkZ, int maxChunkZ,
                                                  ClientChunkCache chunkCache,
                                                  boolean includeDiagonals,
                                                  ChunkPos playerChunk,
                                                  int activeDistance) {
        // Check cardinal neighbors, optionally diagonals, that are inside the export region.
        int[][] offsets = includeDiagonals
            ? new int[][]{
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
            }
            : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] off : offsets) {
            int nx = chunkPos.x + off[0];
            int nz = chunkPos.z + off[1];
            if (nx < minChunkX || nx > maxChunkX || nz < minChunkZ || nz > maxChunkZ) {
                continue; // Outside export bounds, ignore
            }
            if (playerChunk != null && activeDistance > 0) {
                int dist = Math.max(Math.abs(nx - playerChunk.x), Math.abs(nz - playerChunk.z));
                if (dist > activeDistance) {
                    continue; // Neighbor is outside visible window; don't block export.
                }
            }
            LevelChunk neighbor = chunkCache.getChunk(nx, nz, false);
            if (neighbor == null || neighbor.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Best-effort check that the chunk has been compiled for rendering (i.e., visible).
     * Uses reflection to avoid hard dependencies on renderer internals; falls back to true on errors.
     */
    private static boolean isChunkRenderable(Level level, ChunkPos chunkPos) {
        if (!(level instanceof ClientLevel)) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getInstance();

            // If the chunk sits outside the player's render distance, the renderer will never compile it.
            // In that case we don't block export on render readiness.
            if (mc.player != null) {
                ChunkPos playerChunk = mc.player.chunkPosition();
                int renderDistance = mc.options.getEffectiveRenderDistance();
                int dx = Math.abs(chunkPos.x - playerChunk.x);
                int dz = Math.abs(chunkPos.z - playerChunk.z);
                if (Math.max(dx, dz) > renderDistance) {
                    return true;
                }
            }

            var renderer = mc.levelRenderer;
            if (renderer == null) return true;

            // Access LevelRenderer.viewArea (mojmap name)
            Field viewAreaField = renderer.getClass().getDeclaredField("viewArea");
            viewAreaField.setAccessible(true);
            Object viewArea = viewAreaField.get(renderer);
            if (viewArea == null) return true;

            // Try to obtain render section/chunk for this ChunkPos.
            Object renderSection = null;
            // Method variants we try: getRenderChunkAt(BlockPos), getRenderSectionAt(BlockPos)
            Method m = null;
            for (String name : new String[]{"getRenderChunkAt", "getRenderSectionAt"}) {
                try {
                    m = viewArea.getClass().getMethod(name, BlockPos.class);
                    renderSection = m.invoke(viewArea, new BlockPos(chunkPos.x << 4, 0, chunkPos.z << 4));
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            // If no method found, try getRenderChunk(int,int,int)
            if (renderSection == null) {
                try {
                    m = viewArea.getClass().getMethod("getRenderChunk", int.class, int.class, int.class);
                    renderSection = m.invoke(viewArea, chunkPos.x << 4, 0, chunkPos.z << 4);
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (renderSection == null) return true; // 无法确认则不阻塞导出

            // Check if renderSection is dirty or uncompiled.
            try {
                Method isDirty = renderSection.getClass().getMethod("isDirty");
                boolean dirty = (boolean) isDirty.invoke(renderSection);
                if (dirty) return false;
            } catch (NoSuchMethodException ignored) {
            }

            // Check compiled object exists.
            try {
                Method getCompiled = null;
                for (String name : new String[]{"getCompiled", "getCompiledChunk", "getCompiledSection"}) {
                    try {
                        getCompiled = renderSection.getClass().getMethod(name);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (getCompiled != null) {
                    Object compiled = getCompiled.invoke(renderSection);
                    if (compiled == null) return false;
                } else {
                    return true; // 无法确认则不阻塞导出
                }
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            // On reflection failure, don't block export.
            return true;
        }
    }

}
