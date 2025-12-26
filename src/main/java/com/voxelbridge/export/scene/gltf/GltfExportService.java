package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.StreamingRegionSampler;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest; // Fixed: missing import
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * glTF-specific export service that orchestrates:
 * 1. Region sampling (format-agnostic)
 * 2. glTF scene building and writing
 *
 * This class handles glTF-specific concerns like:
 * - Output directory structure (gltf/ subfolder)
 * - File naming conventions
 * - glTF scene builder configuration
 */
@OnlyIn(Dist.CLIENT)
public final class GltfExportService {

    private GltfExportService() {}

    public static Path exportRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    Path outDir) throws IOException {
        VoxelBridgeLogger.initialize(outDir);
        String banner = "============================================================";
        VoxelBridgeLogger.info(LogModule.GLTF, banner);
        VoxelBridgeLogger.info(LogModule.GLTF, "*** GLTF EXPORT STARTED ***");
        VoxelBridgeLogger.info(LogModule.GLTF, banner);

        // Ensure output directory exists
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        // Calculate region bounds
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        // Generate file name based on region bounds
        String baseName = String.format("region_%d_%d_%d__%d_%d_%d",
                minX, minY, minZ, maxX, maxY, maxZ);

        // Create glTF output directory
        Path gltfDir = outDir.resolve("gltf");
        if (!Files.exists(gltfDir)) {
            Files.createDirectories(gltfDir);
        }

        VoxelBridgeLogger.info(LogModule.GLTF, "Output directory: " + gltfDir);
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("Region: X[%d to %d], Y[%d to %d], Z[%d to %d]",
                minX, maxX, minY, maxY, minZ, maxZ));

        // Initialize export context
        Minecraft mc = Minecraft.getInstance();
        ExportContext ctx = new ExportContext(mc);
        ctx.resetConsumedBlocks();
        ctx.clearTextureState();
        ctx.setBlockEntityExportEnabled(true);
        ctx.setCoordinateMode(ExportRuntimeConfig.getCoordinateMode());
        ctx.setVanillaRandomTransformEnabled(ExportRuntimeConfig.isVanillaRandomTransformEnabled());
        ctx.setDiscoveryMode(false);

        // Initialize reserved slots (must be done before any texture registration)
        TextureAtlasManager.initializeReservedSlots(ctx);
        com.voxelbridge.export.texture.ColorMapManager.initializeReservedSlots(ctx);

        VoxelBridgeLogger.info(LogModule.GLTF, "[GLTF] Starting glTF export with format-agnostic sampler");

        // Fixed: CTM debug logging initialization
        // CTM debug logging is now handled internally by CtmDetector
        // BlockExporter.initializeCTMDebugLog(outDir);  // REMOVED

        // Clear BlockEntity texture registry for new export
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        long tTotal = VoxelBridgeLogger.now();

        // Single-pass sampling: collect geometry and texture usage together
        ctx.setDiscoveryMode(false);
        ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "Sampling blocks");
        SceneSink sceneSink = new GltfSceneBuilder(ctx, gltfDir);
        long tSampling = VoxelBridgeLogger.now();
        StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);
        VoxelBridgeLogger.duration("block_sampling", VoxelBridgeLogger.elapsedSince(tSampling));
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

        // OPTIMIZATION: Removed forced GC calls to eliminate 1-5 second Full GC pauses
        // Let JVM manage GC automatically for better throughput
        // System.gc();
        // try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

        // Texture export is handled by TextureExportPipeline in GltfSceneBuilder
        SceneWriteRequest request = new SceneWriteRequest(baseName, gltfDir);

        // OPTIMIZATION: Removed second forced GC call
        // System.gc();
        // try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

        // Write synchronously to ensure the glTF file is created.
        Path outputPath = null;
        try {
            VoxelBridgeLogger.memory("before_geometry_write");
            long tSceneWrite = VoxelBridgeLogger.now();
            outputPath = sceneSink.write(request);
            VoxelBridgeLogger.duration("geometry_write", VoxelBridgeLogger.elapsedSince(tSceneWrite));
            VoxelBridgeLogger.memory("after_geometry_write");
            VoxelBridgeLogger.duration("total_export", VoxelBridgeLogger.elapsedSince(tTotal));

            // Validate output file exists.
            if (outputPath == null || !Files.exists(outputPath)) {
                throw new IOException("Export failed: Output file does not exist at " + outputPath);
            }

            VoxelBridgeLogger.info(LogModule.GLTF, "[GLTF] Export complete: " + outputPath);
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.COMPLETE, "Complete");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            VoxelBridgeLogger.info(LogModule.GLTF, banner);
            VoxelBridgeLogger.info(LogModule.GLTF, "*** EXPORT COMPLETED SUCCESSFULLY ***");
            VoxelBridgeLogger.info(LogModule.GLTF, "Output: " + outputPath);
            VoxelBridgeLogger.info(LogModule.GLTF, banner);
        } catch (OutOfMemoryError e) {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            String errorMsg = "OutOfMemoryError during geometry_write";
            VoxelBridgeLogger.error(LogModule.GLTF, errorMsg);
            VoxelBridgeLogger.error(LogModule.GLTF, "Heap used: " + (used / 1024 / 1024) + " MB");
            VoxelBridgeLogger.error(LogModule.GLTF, "Heap max: " + (max / 1024 / 1024) + " MB");
            VoxelBridgeLogger.error(LogModule.GLTF, "Usage: " + ((used * 100) / max) + "%");
            VoxelBridgeLogger.memory("oom_crash");
            VoxelBridgeLogger.error(LogModule.GLTF, "[GLTF][ERROR] OutOfMemoryError: " + e.getMessage(), e);
            throw e;  // Re-throw to propagate the error
        } catch (Exception e) {
            String errorMsg = "Export failed: " + e.getClass().getName() + ": " + e.getMessage();
            VoxelBridgeLogger.error(LogModule.GLTF, errorMsg, e);

            // Display user-friendly error message
            mc.execute(() -> {
                if (mc.player != null) {
                    String userMsg = "§cExport failed: " + e.getMessage();
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(userMsg), false);
                }
            });

            throw new IOException("Export failed during write phase: " + e.getMessage(), e);  // Re-throw as IOException
        } finally {
            ctx.clearTextureState();
            VoxelBridgeLogger.close();
        }

        // Return the glTF output path (write completed).
        return outputPath;
    }
}
