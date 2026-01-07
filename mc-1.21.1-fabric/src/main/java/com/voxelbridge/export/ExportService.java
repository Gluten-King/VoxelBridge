package com.voxelbridge.export;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Delegates export operations to GltfExportService.
 * Kept for backward compatibility.
 */
public final class ExportService {

    private ExportService() {}

    /**
     * Primary export entry point - delegates to glTF export.
     */
    public static Path exportRegion(World level,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    Path outDir) throws IOException {
        return com.voxelbridge.export.scene.gltf.GltfExportService.exportRegion(level, pos1, pos2, outDir);
    }

    /**
     * Pre-scans region to collect all tint variants.
     * This is a placeholder - glTF export handles this internally.
     */
    public static void collectTintVariants(World level,
                                           BlockPos pos1,
                                           BlockPos pos2,
                                           ExportContext ctx) {
        if (!(level instanceof ClientWorld clientLevel)) {
            throw new IllegalStateException("[VoxelBridge] Must run on client side!");
        }

        ClientChunkManager chunkCache = clientLevel.getChunkManager();

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
                WorldChunk chunk = chunkCache.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
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
