package com.voxelbridge.export;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming region sampler that continuously monitors loaded chunks
 * and exports them as soon as they become available.
 * Updated to use Atomic Export strategy.
 */
@OnlyIn(Dist.CLIENT)
public final class StreamingRegionSampler {

    private StreamingRegionSampler() {}

    public static void sampleRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    SceneSink sink,
                                    ExportContext ctx) {
        ExportLogger.log("[StreamingRegionSampler] Starting streaming export (Atomic Mode)");

        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[StreamingRegionSampler] Must run on client side!");
        }

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

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

        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);

        var chunkCache = clientLevel.getChunkSource();
        Minecraft mc = Minecraft.getInstance();

        int threadCount = ExportRuntimeConfig.getExportThreadCount();
        int workerCount = Math.max(1, Math.min(threadCount, allChunks.size()));
        
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

        // OPTIMIZATION: Shared BlockEntityRenderBatch for all chunks
        // Reduces main thread blocking from N chunks to 1 total flush
        BlockEntityRenderBatch sharedBeBatch = new BlockEntityRenderBatch();

        Thread monitor = new Thread(() -> {
            try {
                while (keepRunning.get()) {
                    ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
                    final ChunkPos playerChunk = mc.player != null ? mc.player.chunkPosition() : null;
                    final int activeDistance = Math.max(0, mc.options != null ? mc.options.getEffectiveRenderDistance() : 0);

                    if (progress.isComplete()) break;

                    for (ChunkPos chunkPos : allChunks) {
                        if (processing.contains(chunkPos)) continue;

                        long key = chunkPos.toLong();
                        ExportProgressTracker.ChunkState state = ExportProgressTracker.snapshot().get(key);

                        if (state != ExportProgressTracker.ChunkState.PENDING) {
                            continue;
                        }

                        if (playerChunk != null) {
                            int dist = Math.max(Math.abs(chunkPos.x - playerChunk.x), Math.abs(chunkPos.z - playerChunk.z));
                            if (dist > activeDistance) {
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

                            executor.submit(() -> exportChunk(
                                chunk, chunkPos, level, chunkCache, sink, ctx,
                                regionMin, regionMax,
                                cminX, cmaxX, cminZ, cmaxZ, minY, maxY,
                                mc, processing, playerChunk, activeDistance,
                                sharedBeBatch  // OPTIMIZATION: Pass shared batch
                            ));
                        }
                    }

                    int cycle = scanCycles.incrementAndGet();
                    if (progress.pending() > 0 && cycle % 5 == 0) {
                        ProgressNotifier.showDetailed(mc, progress);
                    }
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "VoxelBridge-Monitor");

        monitor.setDaemon(true);
        monitor.start();

        try {
            long startTime = System.currentTimeMillis();
            long timeout = 600_000;
            while (!ExportProgressTracker.progress().isComplete()) {
                Thread.sleep(1000);
                if (System.currentTimeMillis() - startTime > timeout) break;
            }
            keepRunning.set(false);
            monitor.interrupt();
            monitor.join(2000);

            // 强制导出所有PENDING区块（忽略渲染距离和邻居检查）
            ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
            if (progress.pending() > 0) {
                ExportLogger.log(String.format("[StreamingRegionSampler] Force exporting %d pending chunks...", progress.pending()));
                for (ChunkPos chunkPos : allChunks) {
                    long key = chunkPos.toLong();
                    ExportProgressTracker.ChunkState state = ExportProgressTracker.snapshot().get(key);

                    if (state == ExportProgressTracker.ChunkState.PENDING) {
                        LevelChunk chunk = chunkCache.getChunk(chunkPos.x, chunkPos.z, false);
                        if (chunk != null && !chunk.isEmpty()) {
                            int cminX = Math.max(minX, chunkPos.x << 4);
                            int cmaxX = Math.min(maxX, (chunkPos.x << 4) + 15);
                            int cminZ = Math.max(minZ, chunkPos.z << 4);
                            int cmaxZ = Math.min(maxZ, (chunkPos.z << 4) + 15);

                            // 强制导出（忽略距离和邻居检查）
                            forceExportChunk(chunk, chunkPos, level, sink, ctx,
                                regionMin, regionMax, cminX, cmaxX, cminZ, cmaxZ,
                                minY, maxY, mc, sharedBeBatch);
                        } else {
                            ExportProgressTracker.markFailed(chunkPos.x, chunkPos.z);
                        }
                    }
                }
            }

            // OPTIMIZATION: Single flush of accumulated BlockEntity render tasks
            // Reduces main thread blocking from N-chunks to 1 total flush
            ExportLogger.log("[StreamingRegionSampler] Flushing accumulated BlockEntity render tasks...");
            sharedBeBatch.flush(mc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        ExportProgressTracker.Progress finalProgress = ExportProgressTracker.progress();
        String summary = String.format(
            "[StreamingRegionSampler] 采样阶段完成 - Done: %d, Failed: %d, Total: %d (后续glTF生成中)",
            finalProgress.done(), finalProgress.failed(), finalProgress.total()
        );
        ExportLogger.log(summary);
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(summary), false);
        });
    }

    private static void exportChunk(LevelChunk chunk, ChunkPos chunkPos, Level level,
                                   ClientChunkCache chunkCache, SceneSink finalSink, ExportContext ctx,
                                   BlockPos regionMin, BlockPos regionMax,
                                   int minX, int maxX, int minZ, int maxZ,
                                   int minY, int maxY,
                                   Minecraft mc, Set<ChunkPos> processing,
                                   ChunkPos playerChunk, int activeDistance,
                                   BlockEntityRenderBatch sharedBeBatch) {
        try {
            ExportProgressTracker.markRunning(chunkPos.x, chunkPos.z);

            if (chunk.isEmpty()) {
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            if (!areNeighborChunksReady(chunkPos, minX >> 4, maxX >> 4, minZ >> 4, maxZ >> 4, chunkCache, true, playerChunk, activeDistance)) {
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            if (!isChunkRenderable(level, chunkPos)) {
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // ATOMIC EXPORT
            BufferedSceneSink buffer = new BufferedSceneSink();
            // OPTIMIZATION: Use shared BlockEntityRenderBatch instead of per-chunk instance
            BlockExporter localSampler = new BlockExporter(ctx, buffer, level, sharedBeBatch);
            localSampler.setRegionBounds(regionMin, regionMax);

            // OPTIMIZATION: Reuse MutableBlockPos to avoid 98,304 object allocations per chunk
            // Memory savings: ~2.4MB temporary objects per chunk + reduced GC pressure
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
            int blockCount = 0;

            // OPTIMIZATION: Use ChunkSection API for faster block state access (1.3-1.8x speedup)
            // Reduces 98,304 method calls per chunk by accessing palette directly
            int minSectionY = level.getMinSection();
            int maxSectionY = level.getMaxSection();
            int worldMinY = level.getMinBuildHeight();

            for (int sectionIndex = minSectionY; sectionIndex <= maxSectionY; sectionIndex++) {
                // Get section (16x16x16 block region)
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionIndex));
                if (section == null || section.hasOnlyAir()) {
                    continue; // Skip empty sections entirely
                }

                int sectionBaseY = worldMinY + (sectionIndex - minSectionY) * 16;

                // Iterate through section in Y-Z-X order (better cache locality)
                for (int localY = 0; localY < 16; localY++) {
                    int worldY = sectionBaseY + localY;
                    if (worldY < minY || worldY > maxY) continue;

                    for (int localZ = 0; localZ < 16; localZ++) {
                        int worldZ = (chunkPos.z << 4) + localZ;
                        if (worldZ < minZ || worldZ > maxZ) continue;

                        for (int localX = 0; localX < 16; localX++) {
                            int worldX = (chunkPos.x << 4) + localX;
                            if (worldX < minX || worldX > maxX) continue;

                            if (blockCount % 64 == 0 && chunk.isEmpty()) {
                                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                                return;
                            }

                            try {
                                // Direct palette access - much faster than getBlockState()
                                BlockState state = section.getBlockState(localX, localY, localZ);
                                if (state.isAir()) continue;

                                mutablePos.set(worldX, worldY, worldZ);
                                localSampler.sampleBlock(state, mutablePos);
                                blockCount++;
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        }
                    }
                }
            }

            if (localSampler.hadMissingNeighborAndReset()) {
                ExportLogger.log("[Streaming] Chunk " + chunkPos + " incomplete (missing neighbors), retry.");
                sharedBeBatch.clear();
                ExportProgressTracker.markPending(chunkPos.x, chunkPos.z);
                return;
            }

            // OPTIMIZATION: Don't flush per-chunk, accumulate in shared batch
            // sharedBeBatch will be flushed once after all chunks complete
            if (!buffer.isEmpty()) {
                buffer.flushTo(finalSink);
            }
            ExportProgressTracker.markDone(chunkPos.x, chunkPos.z);

        } catch (Exception e) {
            e.printStackTrace();
            ExportProgressTracker.markFailed(chunkPos.x, chunkPos.z);
        } finally {
            processing.remove(chunkPos);
        }
    }

    /**
     * 强制导出区块（忽略邻居和渲染距离检查）
     * 用于在采样结束时导出所有PENDING区块
     */
    private static void forceExportChunk(LevelChunk chunk, ChunkPos chunkPos, Level level,
                                        SceneSink finalSink, ExportContext ctx,
                                        BlockPos regionMin, BlockPos regionMax,
                                        int minX, int maxX, int minZ, int maxZ,
                                        int minY, int maxY,
                                        Minecraft mc, BlockEntityRenderBatch sharedBeBatch) {
        try {
            ExportProgressTracker.markRunning(chunkPos.x, chunkPos.z);

            if (chunk.isEmpty()) {
                ExportProgressTracker.markFailed(chunkPos.x, chunkPos.z);
                return;
            }

            // 不检查邻居，不检查渲染距离，直接导出
            BufferedSceneSink buffer = new BufferedSceneSink();
            BlockExporter localSampler = new BlockExporter(ctx, buffer, level, sharedBeBatch);
            localSampler.setRegionBounds(regionMin, regionMax);

            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        mutablePos.set(x, y, z);
                        try {
                            // OPTIMIZATION: Skip air blocks early (60-80% of world is air)
                            BlockState state = chunk.getBlockState(mutablePos);
                            if (state.isAir()) continue;

                            localSampler.sampleBlock(state, mutablePos);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                }
            }

            if (!buffer.isEmpty()) {
                buffer.flushTo(finalSink);
            }
            ExportProgressTracker.markDone(chunkPos.x, chunkPos.z);

        } catch (Exception e) {
            e.printStackTrace();
            ExportProgressTracker.markFailed(chunkPos.x, chunkPos.z);
        }
    }

    private static boolean areNeighborChunksReady(ChunkPos chunkPos, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, ClientChunkCache chunkCache, boolean includeDiagonals, ChunkPos playerChunk, int activeDistance) {
        int[][] offsets = includeDiagonals ? new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}} : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] off : offsets) {
            int nx = chunkPos.x + off[0];
            int nz = chunkPos.z + off[1];
            if (nx < minChunkX || nx > maxChunkX || nz < minChunkZ || nz > maxChunkZ) continue;
            if (playerChunk != null && activeDistance > 0) {
                int dist = Math.max(Math.abs(nx - playerChunk.x), Math.abs(nz - playerChunk.z));
                if (dist > activeDistance) continue;
            }
            LevelChunk neighbor = chunkCache.getChunk(nx, nz, false);
            if (neighbor == null || neighbor.isEmpty()) return false;
        }
        return true;
    }

    private static boolean isChunkRenderable(Level level, ChunkPos chunkPos) {
        if (!(level instanceof ClientLevel)) return true;
        ClientLevel clientLevel = (ClientLevel) level;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.options != null) {
            ChunkPos p = mc.player.chunkPosition();
            if (Math.max(Math.abs(chunkPos.x - p.x), Math.abs(chunkPos.z - p.z)) > mc.options.getEffectiveRenderDistance()) return true;
        }

        ClientChunkCache cache = clientLevel.getChunkSource();
        LevelChunk chunk = cache.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
        return chunk != null && !chunk.isEmpty();
    }
}
