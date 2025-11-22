package com.voxelbridge.export;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.scene.BufferedSceneSink;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.ProgressNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
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
 * <p>
 * Updated to use Atomic Export strategy to prevent missing faces and duplicates.
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
        ExportLogger.log("[StreamingRegionSampler] Starting streaming export (Atomic Mode)");

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

        // NOTE: We do NOT create a global BlockExporter here anymore.
        // Each worker thread will create its own local exporter to ensure thread safety.
        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);

        var chunkCache = clientLevel.getChunkSource();
        Minecraft mc = Minecraft.getInstance();

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
                        if (processing.contains(chunkPos)) {
                            continue;
                        }

                        long key = chunkPos.toLong();
                        ExportProgressTracker.ChunkState state = ExportProgressTracker.snapshot().get(key);

                        if (state != ExportProgressTracker.ChunkState.PENDING &&
                            state != ExportProgressTracker.ChunkState.RETRY) {
                            continue;
                        }

                        // Distance check logic
                        if (playerChunk != null) {
                            int dist = Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z));
                            if (dist > activeDistance) {
                                if (state == ExportProgressTracker.ChunkState.RETRY) {
                                    ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                                }
                                continue;
                            }
                        }

                        LevelChunk chunk = chunkCache.getChunk(chunkPos.x, chunkPos.z, false);
                        if (chunk != null && !chunk.isEmpty()) {
                            processing.add(chunkPos);

                            int cminX = Math.max(minX, chunkPos.x << 4);
                            int cmaxX = Math.min(maxX, (chunkPos.x << 4) + 15);
                            int cminZ = Math.max(minZ, chunkPos.z << 4);
                            int cmaxZ = Math.min(maxZ, (chunkPos.z << 4) + 15);

                            // Submit export task with GLOBAL sink and context
                            executor.submit(() -> exportChunk(
                                chunk, chunkPos, level, chunkCache, sink, ctx,
                                regionMin, regionMax,
                                cminX, cmaxX, cminZ, cmaxZ, minY, maxY,
                                mc, processing, playerChunk, activeDistance
                            ));

                            submitted++;
                        }
                    }

                    int cycle = scanCycles.incrementAndGet();
                    if (submitted > 0 || cycle % 10 == 0) {
                        ExportLogger.log(String.format(
                            "[StreamingRegionSampler] Scan #%d: submitted %d | Done: %d, Retry: %d, Failed: %d, Pending: %d",
                            cycle, submitted, progress.done(), progress.retrying(), progress.failed(), progress.pending()
                        ));
                    }

                    if (submitted > 0 || cycle % 5 == 0) {
                        ProgressNotifier.showDetailed(mc, progress);
                    }

                    Thread.sleep(200);
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

        try {
            long startTime = System.currentTimeMillis();
            long timeout = 600_000; // 10 minutes maximum

            while (!ExportProgressTracker.progress().isComplete()) {
                Thread.sleep(1000);
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
                                    ClientChunkCache chunkCache,
                                    SceneSink finalSink, // The global target sink
                                    ExportContext ctx,
                                    BlockPos regionMin, BlockPos regionMax,
                                    int minX, int maxX, int minZ, int maxZ,
                                    int minY, int maxY,
                                    Minecraft mc, Set<ChunkPos> processing,
                                    ChunkPos playerChunk, int activeDistance) {
        try {
            ExportProgressTracker.markRunning(chunkPos.x, chunkPos.z);

            if (chunk.isEmpty()) {
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Check neighbors availability (Atomic requirement)
            if (!areNeighborChunksReady(chunkPos, minX >> 4, maxX >> 4, minZ >> 4, maxZ >> 4, chunkCache, true, playerChunk, activeDistance)) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) neighbors not ready, retrying later",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Check render visibility (Atomic requirement)
            if (!isChunkRenderable(level, chunkPos)) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) not render-ready, retrying later",
                    chunkPos.x, chunkPos.z
                ));
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // --- ATOMIC EXPORT START ---
            // Create a local buffer and local exporter for this thread
            BufferedSceneSink buffer = new BufferedSceneSink();
            BlockExporter localSampler = new BlockExporter(ctx, buffer, level);
            localSampler.setRegionBounds(regionMin, regionMax);

            int blockCount = 0;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        // Check if chunk unloaded mid-process
                        if (blockCount % 64 == 0 && chunk.isEmpty()) {
                            ExportLogger.log(String.format(
                                "[StreamingRegionSampler] Chunk (%d, %d) unloaded during export",
                                chunkPos.x, chunkPos.z
                            ));
                            ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                            return;
                        }

                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = chunk.getBlockState(pos);

                        try {
                            localSampler.sampleBlock(state, pos);
                            blockCount++;
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                }
            }

            // Check if any block failed due to missing neighbors during export
            if (localSampler.hadMissingNeighborAndReset()) {
                ExportLogger.log(String.format(
                    "[StreamingRegionSampler] Chunk (%d, %d) incomplete (missing neighbor), discarding buffer and retrying",
                    chunkPos.x, chunkPos.z
                ));
                // Discard buffer, mark as pending retry
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Validate chunk state one last time
            if (chunk.isEmpty()) {
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // Success! Flush the buffer to the main file
            if (!buffer.isEmpty()) {
                buffer.flushTo(finalSink);
            }

            ExportProgressTracker.markDone(chunkPos.x, chunkPos.z);
            ExportLogger.log(String.format(
                "[StreamingRegionSampler] Chunk (%d, %d) exported atomically (%d blocks, %d quads)",
                chunkPos.x, chunkPos.z, blockCount, buffer.getQuadCount()
            ));
            // --- ATOMIC EXPORT END ---

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
                continue;
            }
            if (playerChunk != null && activeDistance > 0) {
                int dist = Math.max(Math.abs(nx - playerChunk.x), Math.abs(nz - playerChunk.z));
                if (dist > activeDistance) {
                    continue;
                }
            }
            LevelChunk neighbor = chunkCache.getChunk(nx, nz, false);
            if (neighbor == null || neighbor.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isChunkRenderable(Level level, ChunkPos chunkPos) {
        if (!(level instanceof ClientLevel)) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
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

            Field viewAreaField = renderer.getClass().getDeclaredField("viewArea");
            viewAreaField.setAccessible(true);
            Object viewArea = viewAreaField.get(renderer);
            if (viewArea == null) return true;

            Object renderSection = null;
            Method m = null;
            for (String name : new String[]{"getRenderChunkAt", "getRenderSectionAt"}) {
                try {
                    m = viewArea.getClass().getMethod(name, BlockPos.class);
                    renderSection = m.invoke(viewArea, new BlockPos(chunkPos.x << 4, 0, chunkPos.z << 4));
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (renderSection == null) {
                try {
                    m = viewArea.getClass().getMethod("getRenderChunk", int.class, int.class, int.class);
                    renderSection = m.invoke(viewArea, chunkPos.x << 4, 0, chunkPos.z << 4);
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (renderSection == null) return true;

            try {
                Method isDirty = renderSection.getClass().getMethod("isDirty");
                boolean dirty = (boolean) isDirty.invoke(renderSection);
                if (dirty) return false;
            } catch (NoSuchMethodException ignored) {
            }

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
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}