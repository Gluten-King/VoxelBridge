package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.StreamingRegionSampler;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.util.debug.BlockEntityDebugLogger;
import com.voxelbridge.util.debug.ExportLogger;
import com.voxelbridge.util.debug.TimeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * VXB-specific export service.
 */
@OnlyIn(Dist.CLIENT)
public final class VxbExportService {

    private VxbExportService() {}

    public static Path exportRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    Path outDir) throws IOException {
        String banner = "============================================================";
        System.out.println(banner);
        System.out.println("[VoxelBridge][VXB] *** VXB EXPORT STARTED ***");
        System.out.println(banner);

        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        String baseName = String.format("region_%d_%d_%d__%d_%d_%d",
            minX, minY, minZ, maxX, maxY, maxZ);

        Path vxbDir = outDir.resolve("vxb");
        if (!Files.exists(vxbDir)) {
            Files.createDirectories(vxbDir);
        }

        System.out.println("[VoxelBridge][VXB] Output directory: " + vxbDir);
        System.out.printf("[VoxelBridge][VXB] Region: X[%d to %d], Y[%d to %d], Z[%d to %d]%n",
            minX, maxX, minY, maxY, minZ, maxZ);

        Minecraft mc = Minecraft.getInstance();
        ExportContext ctx = new ExportContext(mc);
        ctx.resetConsumedBlocks();
        ctx.clearTextureState();
        ctx.setBlockEntityExportEnabled(true);
        ctx.setCoordinateMode(ExportRuntimeConfig.getCoordinateMode());
        ctx.setVanillaRandomTransformEnabled(ExportRuntimeConfig.isVanillaRandomTransformEnabled());
        ctx.setDiscoveryMode(false);

        TextureAtlasManager.initializeReservedSlots(ctx);
        com.voxelbridge.export.texture.ColorMapManager.initializeReservedSlots(ctx);

        ExportLogger.initialize(outDir);
        TimeLogger.initialize(outDir);
        ExportLogger.log("[VXB] Starting VXB export with format-agnostic sampler");
        BlockEntityDebugLogger.initialize(outDir);
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        long tTotal = TimeLogger.now();
        Path outputPath;

        try {
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "采样方块");
            SceneWriteRequest request = new SceneWriteRequest(baseName, vxbDir);
            SceneSink sceneSink = new VxbSceneBuilder(ctx, vxbDir, baseName);
            long tSampling = TimeLogger.now();
            StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);
            TimeLogger.logDuration("block_sampling", TimeLogger.elapsedSince(tSampling));
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "生成纹理");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            long tAtlas = TimeLogger.now();
            TextureAtlasManager.generateAllAtlases(ctx, vxbDir);
            com.voxelbridge.export.texture.ColorMapManager.generateColorMaps(ctx, vxbDir);
            TimeLogger.logDuration("texture_atlas_generation", TimeLogger.elapsedSince(tAtlas));

            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

            TimeLogger.logMemory("before_vxb_write");
            long tWrite = TimeLogger.now();
            outputPath = sceneSink.write(request);
            TimeLogger.logDuration("vxb_write", TimeLogger.elapsedSince(tWrite));
            TimeLogger.logMemory("after_vxb_write");
            TimeLogger.logDuration("total_export", TimeLogger.elapsedSince(tTotal));

            if (outputPath == null || !Files.exists(outputPath)) {
                throw new IOException("Export failed: Output file does not exist at " + outputPath);
            }

            ExportLogger.log("[VXB] Export complete: " + outputPath);
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.COMPLETE, "完成");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            System.out.println(banner);
            System.out.println("[VoxelBridge][VXB] *** EXPORT COMPLETED SUCCESSFULLY ***");
            System.out.println("[VoxelBridge][VXB] Output: " + outputPath);
            System.out.println(banner);
        } catch (Exception e) {
            String errorMsg = "[VXB][ERROR] Export failed: " + e.getClass().getName() + ": " + e.getMessage();
            ExportLogger.log(errorMsg);
            ExportLogger.log("[VXB][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            System.err.println(errorMsg);
            e.printStackTrace();
            throw new IOException("Export failed during write phase: " + e.getMessage(), e);
        } finally {
            BlockEntityDebugLogger.close();
            ExportLogger.close();
            TimeLogger.close();
        }

        return outputPath;
    }
}
