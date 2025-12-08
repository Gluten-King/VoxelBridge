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
        ExportLogger.log("[GLTF] Starting glTF export with format-agnostic sampler");
        BlockEntityDebugLogger.initialize(outDir);

        // 修复: 初始化 CTM 调试日志
        BlockExporter.initializeCTMDebugLog(outDir);

        // Clear BlockEntity texture registry for new export
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        // Create glTF-specific scene sink
        SceneSink sceneSink = new GltfSceneBuilder(ctx, gltfDir);

        // Sample region geometry using streaming sampler (format-agnostic)
        StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);

        // Write glTF output
        SceneWriteRequest request = new SceneWriteRequest(baseName, gltfDir);
        // Generate atlases if needed (place under gltf dir)
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS) {
            TextureAtlasManager.generateAllAtlases(ctx, gltfDir);
            // Pack BlockEntity textures into atlas (ATLAS mode)
            ExportLogger.log("[GLTF] Packing BlockEntity textures into atlas");
            com.voxelbridge.export.texture.BlockEntityTextureManager.packIntoAtlas(ctx, gltfDir);
        } else {
            // Export BlockEntity textures as individual files (INDIVIDUAL mode)
            com.voxelbridge.export.texture.BlockEntityTextureManager.exportTextures(ctx, gltfDir);
        }
        Path output;
        try {
            output = sceneSink.write(request);
            ExportLogger.log("[GLTF] Export complete: " + output);
            System.out.println(banner);
            System.out.println("[VoxelBridge][GLTF] *** EXPORT COMPLETED SUCCESSFULLY ***");
            System.out.println("[VoxelBridge][GLTF] Output: " + output);
            System.out.println(banner);
            return output;
        } finally {
            BlockEntityDebugLogger.close();
            ExportLogger.close();
            BlockExporter.closeCTMDebugLog();
        }
    }
}
