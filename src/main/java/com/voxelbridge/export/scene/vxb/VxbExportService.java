package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.StreamingRegionSampler;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.util.client.ProgressNotifier;
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
        VoxelBridgeLogger.initialize(outDir);
        String banner = "============================================================";
        VoxelBridgeLogger.info(LogModule.VXB, banner);
        VoxelBridgeLogger.info(LogModule.VXB, "*** VXB EXPORT STARTED ***");
        VoxelBridgeLogger.info(LogModule.VXB, banner);

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

        VoxelBridgeLogger.info(LogModule.VXB, "Output directory: " + vxbDir);
        VoxelBridgeLogger.info(LogModule.VXB, String.format("Region: X[%d to %d], Y[%d to %d], Z[%d to %d]",
            minX, maxX, minY, maxY, minZ, maxZ));

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

        // Initialize unified logging system
        VoxelBridgeLogger.info(LogModule.VXB, "[VXB] Starting VXB export with format-agnostic sampler");
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        long tTotal = VoxelBridgeLogger.now();
        Path outputPath;

        try {
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "Sampling blocks");
            SceneWriteRequest request = new SceneWriteRequest(baseName, vxbDir);
            SceneSink sceneSink = new VxbSceneBuilder(ctx, vxbDir, baseName);
            long tSampling = VoxelBridgeLogger.now();
            StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);
            VoxelBridgeLogger.duration("block_sampling", VoxelBridgeLogger.elapsedSince(tSampling));
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "Generating textures");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            long tAtlas = VoxelBridgeLogger.now();
            TextureAtlasManager.generateAllAtlases(ctx, vxbDir);
            com.voxelbridge.export.texture.ColorMapManager.generateColorMaps(ctx, vxbDir);
            EntityTextureManager.dumpAll(ctx, vxbDir);
            if (ExportRuntimeConfig.isAnimationEnabled()) {
                java.util.Set<String> animationWhitelist = new java.util.HashSet<>();
                animationWhitelist.addAll(ctx.getAtlasBook().keySet());
                animationWhitelist.addAll(ctx.getCachedSpriteKeys());
                TextureAtlasManager.exportDetectedAnimations(ctx, vxbDir, animationWhitelist);
            }
            VoxelBridgeLogger.duration("texture_atlas_generation", VoxelBridgeLogger.elapsedSince(tAtlas));

            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

            VoxelBridgeLogger.memory("before_vxb_write");
            long tWrite = VoxelBridgeLogger.now();
            outputPath = sceneSink.write(request);
            VoxelBridgeLogger.duration("vxb_write", VoxelBridgeLogger.elapsedSince(tWrite));
            VoxelBridgeLogger.memory("after_vxb_write");
            VoxelBridgeLogger.duration("total_export", VoxelBridgeLogger.elapsedSince(tTotal));

            if (outputPath == null || !Files.exists(outputPath)) {
                throw new IOException("Export failed: Output file does not exist at " + outputPath);
            }

            VoxelBridgeLogger.info(LogModule.VXB, "[VXB] Export complete: " + outputPath);
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.COMPLETE, "Complete");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            VoxelBridgeLogger.info(LogModule.VXB, banner);
            VoxelBridgeLogger.info(LogModule.VXB, "*** EXPORT COMPLETED SUCCESSFULLY ***");
            VoxelBridgeLogger.info(LogModule.VXB, "Output: " + outputPath);
            VoxelBridgeLogger.info(LogModule.VXB, banner);
        } catch (Exception e) {
            String errorMsg = "Export failed: " + e.getClass().getName() + ": " + e.getMessage();
            VoxelBridgeLogger.error(LogModule.VXB, "[VXB][ERROR] " + errorMsg);
            VoxelBridgeLogger.error(LogModule.VXB, "[VXB][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                VoxelBridgeLogger.info(LogModule.VXB, "    at " + element.toString());
            }
            VoxelBridgeLogger.error(LogModule.VXB, errorMsg, e);
            throw new IOException("Export failed during write phase: " + e.getMessage(), e);
        } finally {
            // Close unified logging system
            VoxelBridgeLogger.close();
        }

        return outputPath;
    }
}



