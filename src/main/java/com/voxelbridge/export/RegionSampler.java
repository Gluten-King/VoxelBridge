package com.voxelbridge.export;

import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.export.scene.BufferedSceneSink;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.ProgressNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Format-agnostic region sampler that collects geometry from a Minecraft region
 * and feeds it into any SceneSink implementation.
 * Updated to use Atomic Export strategy.
 */
@OnlyIn(Dist.CLIENT)
public final class RegionSampler {

    private RegionSampler() {}

    public static void sampleRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    SceneSink sink,
                                    ExportContext ctx) {
        ExportLogger.log("[RegionSampler] Starting region sampling (Atomic Mode)");

        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[RegionSampler] Must run on client side!");
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

        BlockPos regionMin = new BlockPos(minX, minY, minZ);
        BlockPos regionMax = new BlockPos(maxX, maxY, maxZ);

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

        List<int[]> chunkBounds = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int cminX = Math.max(minX, cx << 4);
                int cmaxX = Math.min(maxX, (cx << 4) + 15);
                int cminZ = Math.max(minZ, cz << 4);
                int cmaxZ = Math.min(maxZ, (cz << 4) + 15);
                chunkBounds.add(new int[]{cx, cz, cminX, cmaxX, cminZ, cmaxZ});
            }
        }

        Set<Long> chunkKeys = chunkBounds.stream()
            .map(b -> new ChunkPos(b[0], b[1]).toLong())
            .collect(Collectors.toSet());
        ExportProgressTracker.initForExport(chunkKeys);

        int workerCount = Math.max(1, Math.min(maxWorkerCount, chunkBounds.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount, factory);
        Minecraft mc = Minecraft.getInstance();

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
                    ExportProgressTracker.markDone(chunkX, chunkZ);
                    updateProgress(processedChunks, chunkBounds.size(), mc);
                    return;
                }

                BufferedSceneSink buffer = new BufferedSceneSink();
                BlockEntityRenderBatch beBatch = new BlockEntityRenderBatch();
                BlockExporter localSampler = new BlockExporter(ctx, buffer, level, beBatch);
                localSampler.setRegionBounds(regionMin, regionMax);

                for (int x = fx0; x <= fx1; x++) {
                    for (int z = fz0; z <= fz1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            try {
                                localSampler.sampleBlock(state, pos);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        }
                    }
                }

                beBatch.flush(mc);
                // In standard export, we flush best-effort even if neighbors missing
                if (!buffer.isEmpty()) {
                    buffer.flushTo(sink);
                }

                ExportProgressTracker.markDone(chunkX, chunkZ);
                updateProgress(processedChunks, chunkBounds.size(), mc);
            }));
        }

        try {
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }

        ExportLogger.log(String.format("[RegionSampler] Sampling complete - processed %d/%d chunks",
            processedChunks.get(), chunkBounds.size()));
    }

    private static void updateProgress(AtomicInteger processedChunks, int total, Minecraft mc) {
        int current = processedChunks.incrementAndGet();
        double percent = 100.0 * current / total;
        if (current % 10 == 0) ProgressNotifier.show(mc, percent, current, total);
    }
}
