package com.voxelbridge.export;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.pipeline.contract.BlockPos3i;
import com.voxelbridge.pipeline.contract.Region3i;
import com.voxelbridge.pipeline.region.ChunkWindowPlan;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming region sampler that continuously monitors loaded chunks
 * and exports them as soon as they become available.
 * Updated to use Atomic Export strategy.
 */
public final class StreamingRegionSampler {

    private StreamingRegionSampler() {}

    public static void sampleRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    IrSink sink,
                                    ExportContext ctx) {
        if (ExportProgressTracker.isAbortRequested()) {
            return;
        }
        VoxelBridgeLogger.info(LogModule.EXPORT, "[StreamingRegionSampler] Starting streaming export (Atomic Mode)");

        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[StreamingRegionSampler] Must run on client side!");
        }

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        ChunkWindowPlan windowPlan = ChunkWindowPlan.create(new Region3i(
            new BlockPos3i(minX, minY, minZ), new BlockPos3i(maxX, maxY, maxZ)));
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        Set<ChunkPos> allChunks = ConcurrentHashMap.newKeySet();
        for (var unit : windowPlan.units()) {
            allChunks.add(new ChunkPos(unit.chunk().x(), unit.chunk().z()));
        }

        Set<Long> chunkKeys = allChunks.stream()
            .map(ChunkPos::pack)
            .collect(java.util.stream.Collectors.toSet());
        ExportProgressTracker.initForExport(chunkKeys);

        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);

        double offsetX = (ctx.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minX + maxX) / 2.0
            : 0;
        double offsetY = (ctx.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minY + maxY) / 2.0
            : 0;
        double offsetZ = (ctx.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minZ + maxZ) / 2.0
            : 0;

        var chunkCache = clientLevel.getChunkSource();
        Minecraft mc = ClientAccessHolder.get().getMinecraft();

        int workerCount = windowPlan.workerCount(
            ctx.session().options().workerThreads(), Runtime.getRuntime().availableProcessors());

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("VoxelBridge-Streaming-" + counter.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
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
        java.util.Set<Integer> processedEntityIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        Thread monitor = new Thread(() -> {
            try {
                while (keepRunning.get()) {
                    if (ExportProgressTracker.isAbortRequested()) {
                        break;
                    }
                    ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
                    final ChunkPos playerChunk = mc.player != null ? mc.player.chunkPosition() : null;
                    final int activeDistance = Math.max(0, mc.options != null ? mc.options.getEffectiveRenderDistance() : 0);

                    if (progress.isComplete()) break;

                    Map<Long, ExportProgressTracker.ChunkState> snapshot = ExportProgressTracker.snapshot();
                    for (ChunkPos chunkPos : allChunks) {
                        if (processing.contains(chunkPos)) continue;
                        if (ExportProgressTracker.isAbortRequested()) {
                            break;
                        }

                        long key = chunkPos.pack();
                        ExportProgressTracker.ChunkState state = snapshot.get(key);

                    if (state != ExportProgressTracker.ChunkState.PENDING) {
                        continue;
                    }

                    if (playerChunk != null) {
                        int dist = Math.max(Math.abs(chunkPos.x() - playerChunk.x()), Math.abs(chunkPos.z() - playerChunk.z()));
                        if (dist > activeDistance) {
                            if (VoxelBridgeLogger.isDebugEnabled(LogModule.EXPORT)) {
                                VoxelBridgeLogger.info(LogModule.EXPORT, "[Streaming] Skip chunk " + chunkPos + " (outside render distance, dist=" + dist + ", active=" + activeDistance + ")");
                            }
                            continue;
                        }
                    }

                    LevelChunk chunk = chunkCache.getChunk(chunkPos.x(), chunkPos.z(), false);
                    if (chunk != null && !chunk.isEmpty()) {
                        processing.add(chunkPos);

                            int cminX = Math.max(minX, chunkPos.x() << 4);
                            int cmaxX = Math.min(maxX, (chunkPos.x() << 4) + 15);
                            int cminZ = Math.max(minZ, chunkPos.z() << 4);
                            int cmaxZ = Math.min(maxZ, (chunkPos.z() << 4) + 15);

                            executor.submit(() -> ChunkExportWorker.exportChunk(
                                chunk, chunkPos, level, chunkCache, sink, ctx,
                                regionMin, regionMax,
                                cminX, cmaxX, cminZ, cmaxZ, minY, maxY,
                            mc, processing, playerChunk, activeDistance,
                            sharedBeBatch, offsetX, offsetY, offsetZ, processedEntityIds  // OPTIMIZATION: Pass shared batch
                        ));
                    } else {
                        String reason = (chunk == null) ? "null" : "empty";
                        if (VoxelBridgeLogger.isDebugEnabled(LogModule.EXPORT)) {
                            VoxelBridgeLogger.info(LogModule.EXPORT, "[Streaming] Chunk " + chunkPos + " not ready (" + reason + "), stay pending");
                        }
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
                if (ExportProgressTracker.isAbortRequested()) {
                    break;
                }
                Thread.sleep(1000);
                if (System.currentTimeMillis() - startTime > timeout) break;
            }
            keepRunning.set(false);
            monitor.interrupt();
            monitor.join(2000);

            // Force-export any remaining pending chunks after timeout.
            ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
            if (progress.pending() > 0 && !ExportProgressTracker.isAbortRequested()) {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.EXPORT)) {
                    VoxelBridgeLogger.info(LogModule.EXPORT, String.format("[StreamingRegionSampler] Force exporting %d pending chunks...", progress.pending()));
                }
                Map<Long, ExportProgressTracker.ChunkState> snapshot = ExportProgressTracker.snapshot();
                for (ChunkPos chunkPos : allChunks) {
                    long key = chunkPos.pack();
                    ExportProgressTracker.ChunkState state = snapshot.get(key);

                    if (state == ExportProgressTracker.ChunkState.PENDING) {
                        LevelChunk chunk = chunkCache.getChunk(chunkPos.x(), chunkPos.z(), false);
                        if (chunk != null && !chunk.isEmpty()) {
                            int cminX = Math.max(minX, chunkPos.x() << 4);
                            int cmaxX = Math.min(maxX, (chunkPos.x() << 4) + 15);
                            int cminZ = Math.max(minZ, chunkPos.z() << 4);
                            int cmaxZ = Math.min(maxZ, (chunkPos.z() << 4) + 15);

                            // Force-export pending chunk using the slow path.
                            ChunkExportWorker.forceExportChunk(chunk, chunkPos, level, sink, ctx,
                                regionMin, regionMax, cminX, cmaxX, cminZ, cmaxZ,
                                minY, maxY, mc, sharedBeBatch, offsetX, offsetY, offsetZ, processedEntityIds);
                        } else {
                            String reason = (chunk == null) ? "null" : "empty";
                            if (VoxelBridgeLogger.isDebugEnabled(LogModule.EXPORT)) {
                                VoxelBridgeLogger.info(LogModule.EXPORT, "[Streaming][Force] Chunk " + chunkPos + " unavailable (" + reason + "), marking failed");
                            }
                            ExportProgressTracker.markFailed(chunkPos.x(), chunkPos.z());
                        }
                    }
                }
            }

            // OPTIMIZATION: Single flush of accumulated BlockEntity render tasks
            // Reduces main thread blocking from N-chunks to 1 total flush
            VoxelBridgeLogger.info(LogModule.EXPORT, "[StreamingRegionSampler] Flushing accumulated BlockEntity render tasks...");
            sharedBeBatch.flush(mc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        ExportProgressTracker.Progress finalProgress = ExportProgressTracker.progress();
        String formatLabel = ExportProgressTracker.getFormatLabel();
        String summary = String.format(
            "[StreamingRegionSampler] Sampling finished for %s - Done:%d Failed:%d Total:%d (scene build running)",
            formatLabel, finalProgress.done(), finalProgress.failed(), finalProgress.total()
        );
        VoxelBridgeLogger.info(LogModule.EXPORT, summary);
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(summary));
            }
        });
    }

}
