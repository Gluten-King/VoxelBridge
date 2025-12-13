package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.StreamingRegionSampler;
import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest; // <--- 修复: 导入缺失的类
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.BlockEntityDebugLogger;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.TimeLogger;
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
        String banner = "============================================================";
        System.out.println(banner);
        System.out.println("[VoxelBridge][GLTF] *** GLTF EXPORT STARTED ***");
        System.out.println(banner);

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

        System.out.println("[VoxelBridge][GLTF] Output directory: " + gltfDir);
        System.out.printf("[VoxelBridge][GLTF] Region: X[%d to %d], Y[%d to %d], Z[%d to %d]%n",
                minX, maxX, minY, maxY, minZ, maxZ);

        // Initialize export context
        Minecraft mc = Minecraft.getInstance();
        ExportContext ctx = new ExportContext(mc);
        ctx.resetConsumedBlocks();
        ctx.clearTextureState();
        ctx.setBlockEntityExportEnabled(true);
        ctx.setCoordinateMode(ExportRuntimeConfig.getCoordinateMode());
        ctx.setVanillaRandomTransformEnabled(ExportRuntimeConfig.isVanillaRandomTransformEnabled());

        // Initialize reserved slots (must be done before any texture registration)
        TextureAtlasManager.initializeReservedSlots(ctx);
        com.voxelbridge.export.texture.ColorMapManager.initializeReservedSlots(ctx);

        // Initialize logging
        ExportLogger.initialize(outDir);
        TimeLogger.initialize(outDir);
        ExportLogger.log("[GLTF] Starting glTF export with format-agnostic sampler");
        BlockEntityDebugLogger.initialize(outDir);

        // 修复: 初始化 CTM 调试日志
        BlockExporter.initializeCTMDebugLog(outDir);

        // Clear BlockEntity texture registry for new export
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        // Create glTF-specific scene sink
        SceneSink sceneSink = new GltfSceneBuilder(ctx, gltfDir);

        long tTotal = TimeLogger.now();
        // Sample region geometry using streaming sampler (format-agnostic)
        long tSampling = TimeLogger.now();
        StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);
        TimeLogger.logDuration("block_sampling", TimeLogger.elapsedSince(tSampling));

        // Write glTF output
        SceneWriteRequest request = new SceneWriteRequest(baseName, gltfDir);
        // Generate atlases if needed (place under gltf dir)
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS) {
            long tAtlas = TimeLogger.now();
            TextureAtlasManager.generateAllAtlases(ctx, gltfDir);
            TimeLogger.logDuration("texture_atlas_generation", TimeLogger.elapsedSince(tAtlas));
            ExportLogger.log("[GLTF] BlockEntity textures merged into main atlas (legacy packer skipped)");
        } else {
            // Export BlockEntity textures as individual files (INDIVIDUAL mode)
            long tBerExport = TimeLogger.now();
            com.voxelbridge.export.texture.BlockEntityTextureManager.exportTextures(ctx, gltfDir);
            TimeLogger.logDuration("blockentity_texture_export", TimeLogger.elapsedSince(tBerExport));
        }
        Path output;
        try {
            TimeLogger.logMemory("before_geometry_write");
            long tSceneWrite = TimeLogger.now();
            output = sceneSink.write(request);
            TimeLogger.logDuration("geometry_write", TimeLogger.elapsedSince(tSceneWrite));
            TimeLogger.logMemory("after_geometry_write");
            TimeLogger.logDuration("total_export", TimeLogger.elapsedSince(tTotal));
            ExportLogger.log("[GLTF] Export complete: " + output);
            System.out.println(banner);
            System.out.println("[VoxelBridge][GLTF] *** EXPORT COMPLETED SUCCESSFULLY ***");
            System.out.println("[VoxelBridge][GLTF] Output: " + output);
            System.out.println(banner);
            return output;
        } catch (OutOfMemoryError e) {
            // Capture OOM diagnostics to timelog
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            System.err.println("[VoxelBridge][CRASH] OutOfMemoryError during geometry_write");
            System.err.println("[VoxelBridge][CRASH] Heap used: " + (used / 1024 / 1024) + " MB");
            System.err.println("[VoxelBridge][CRASH] Heap max: " + (max / 1024 / 1024) + " MB");
            System.err.println("[VoxelBridge][CRASH] Usage: " + ((used * 100) / max) + "%");
            TimeLogger.logMemory("oom_crash");
            throw e;
        } catch (Exception e) {
            ExportLogger.log("[GLTF][ERROR] Export failed: " + e);
            e.printStackTrace();
            throw e;
        } finally {
            BlockEntityDebugLogger.close();
            ExportLogger.close();
            BlockExporter.closeCTMDebugLog();
            TimeLogger.close();
        }
    }
}
