package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.StreamingRegionSampler;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.exporter.BlockExporter;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest; // <--- 修复: 导入缺失的类
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.debug.BlockEntityDebugLogger;
import com.voxelbridge.util.debug.ExportLogger;
import com.voxelbridge.util.debug.TimeLogger;
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
        ctx.setDiscoveryMode(false);

        // Initialize reserved slots (must be done before any texture registration)
        TextureAtlasManager.initializeReservedSlots(ctx);
        com.voxelbridge.export.texture.ColorMapManager.initializeReservedSlots(ctx);

        // Initialize logging
        ExportLogger.initialize(outDir);
        TimeLogger.initialize(outDir);
        ExportLogger.log("[GLTF] Starting glTF export with format-agnostic sampler");
        BlockEntityDebugLogger.initialize(outDir);

        // 修复: 初始化 CTM 调试日志
        // CTM debug logging is now handled internally by CtmDetector
        // BlockExporter.initializeCTMDebugLog(outDir);  // REMOVED

        // Clear BlockEntity texture registry for new export
        com.voxelbridge.export.texture.BlockEntityTextureManager.clear(ctx);

        long tTotal = TimeLogger.now();

        // Single-pass sampling: collect geometry and texture usage together
        ctx.setDiscoveryMode(false);
        ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "采样方块");
        SceneSink sceneSink = new GltfSceneBuilder(ctx, gltfDir);
        long tSampling = TimeLogger.now();
        StreamingRegionSampler.sampleRegion(level, pos1, pos2, sceneSink, ctx);
        TimeLogger.logDuration("block_sampling", TimeLogger.elapsedSince(tSampling));
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

        // Suggest GC between sampling and atlas generation to reduce peak memory
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

        // Generate atlases if needed (place under gltf dir)
        SceneWriteRequest request = new SceneWriteRequest(baseName, gltfDir);
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS) {
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "生成纹理图集");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            long tAtlas = TimeLogger.now();
            TextureAtlasManager.generateAllAtlases(ctx, gltfDir);
            TimeLogger.logDuration("texture_atlas_generation", TimeLogger.elapsedSince(tAtlas));
            ExportLogger.log("[GLTF] BlockEntity textures merged into main atlas (legacy packer skipped)");
        } else {
            long tBerExport = TimeLogger.now();
            com.voxelbridge.export.texture.BlockEntityTextureManager.exportTextures(ctx, gltfDir);
            TimeLogger.logDuration("blockentity_texture_export", TimeLogger.elapsedSince(tBerExport));
        }

        // Suggest GC between texture generation and geometry write
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ignore */ }

        // 同步写出，确保 glTF 文件生成
        Path outputPath = null;
        try {
            TimeLogger.logMemory("before_geometry_write");
            long tSceneWrite = TimeLogger.now();
            outputPath = sceneSink.write(request);
            TimeLogger.logDuration("geometry_write", TimeLogger.elapsedSince(tSceneWrite));
            TimeLogger.logMemory("after_geometry_write");
            TimeLogger.logDuration("total_export", TimeLogger.elapsedSince(tTotal));

            // 验证输出文件存在
            if (outputPath == null || !Files.exists(outputPath)) {
                throw new IOException("Export failed: Output file does not exist at " + outputPath);
            }

            ExportLogger.log("[GLTF] Export complete: " + outputPath);
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.COMPLETE, "完成");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            System.out.println(banner);
            System.out.println("[VoxelBridge][GLTF] *** EXPORT COMPLETED SUCCESSFULLY ***");
            System.out.println("[VoxelBridge][GLTF] Output: " + outputPath);
            System.out.println(banner);
        } catch (OutOfMemoryError e) {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            String errorMsg = "[VoxelBridge][CRASH] OutOfMemoryError during geometry_write";
            System.err.println(errorMsg);
            System.err.println("[VoxelBridge][CRASH] Heap used: " + (used / 1024 / 1024) + " MB");
            System.err.println("[VoxelBridge][CRASH] Heap max: " + (max / 1024 / 1024) + " MB");
            System.err.println("[VoxelBridge][CRASH] Usage: " + ((used * 100) / max) + "%");
            TimeLogger.logMemory("oom_crash");
            ExportLogger.log("[GLTF][ERROR] OutOfMemoryError: " + e.getMessage());
            ExportLogger.log("[GLTF][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            e.printStackTrace();
            throw e;  // Re-throw to propagate the error
        } catch (Exception e) {
            String errorMsg = "[GLTF][ERROR] Export failed: " + e.getClass().getName() + ": " + e.getMessage();
            ExportLogger.log(errorMsg);
            ExportLogger.log("[GLTF][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            System.err.println(errorMsg);
            e.printStackTrace();

            // 显示用户友好的错误消息
            mc.execute(() -> {
                if (mc.player != null) {
                    String userMsg = "§c导出失败: " + e.getMessage();
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(userMsg), false);
                }
            });

            throw new IOException("Export failed during write phase: " + e.getMessage(), e);  // Re-throw as IOException
        } finally {
            BlockEntityDebugLogger.close();
            ExportLogger.close();
            // CTM debug logging is now handled internally by CtmDetector
            // BlockExporter.closeCTMDebugLog();  // REMOVED
            TimeLogger.close();
        }

        // 返回目标 glTF 路径（此时已同步写完）
        return outputPath;
    }
}
