package com.voxelbridge.export;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Delegates export operations to GltfExportService.
 * Kept for backward compatibility.
 */
@OnlyIn(Dist.CLIENT)
public final class ExportService {

    private ExportService() {}

    /**
     * Primary export entry point - delegates to glTF export.
     */
    public static Path exportRegion(Level level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    Path outDir) throws IOException {
        return com.voxelbridge.export.scene.gltf.GltfExportService.exportRegion(level, pos1, pos2, outDir);
    }

    /**
     * Pre-scans region to collect all tint variants.
     * This is a placeholder - glTF export handles this internally.
     */
    public static void collectTintVariants(Level level,
                                           BlockPos pos1,
                                           BlockPos pos2,
                                           ExportContext ctx) throws IOException {
        if (!(level instanceof ClientLevel clientLevel)) {
            throw new IllegalStateException("[VoxelBridge] Must run on client side!");
        }

        ClientChunkCache chunkCache = clientLevel.getChunkSource();

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

        VoxelBridgeLogger.info(LogModule.EXPORT, "[VoxelBridge] Pre-scanning region for tint variants...");
        VoxelBridgeLogger.info(LogModule.EXPORT, "Pre-scanning region for tint variants...");

        int scanned = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = chunkCache.getChunk(cx, cz, false);
                if (chunk == null || chunk.isEmpty()) {
                    VoxelBridgeLogger.warn(LogModule.EXPORT, String.format("[VoxelBridge][WARN] Chunk (%d,%d) unavailable during tint scan", cx, cz));
                    continue;
                }
                scanned++;
            }
        }

        ctx.resetConsumedBlocks();
        ctx.clearTextureState();
        VoxelBridgeLogger.info(LogModule.EXPORT, "Tint scan complete - scanned " + scanned + " chunks");
        VoxelBridgeLogger.info(LogModule.EXPORT, "[VoxelBridge] Tint scan complete");
    }
}
