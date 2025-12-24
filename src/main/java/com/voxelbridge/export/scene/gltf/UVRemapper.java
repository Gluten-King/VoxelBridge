package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.UvRemapUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.DoubleConsumer;

/**
 * UV Remapper: Sequentially reads uvraw.bin, remaps UV coordinates based on atlas, and streams to finaluv.bin.
 */
final class UVRemapper {
    private static final int CHUNK_SIZE_QUADS = 65536; // 64K quads per chunk
    private static final int BYTES_PER_QUAD_UV = 64;   // UV data size per quad

    private UVRemapper() {}

    /**
     * Remaps UV coordinates
     * @param geometryBin geometry.bin path (for reading spriteId)
     * @param uvrawBin uvraw.bin path
     * @param finaluvBin output finaluv.bin path
     * @param spriteIndex sprite index
     * @param ctx export context
     */
    static void remapUVs(
        Path geometryBin,
        Path uvrawBin,
        Path finaluvBin,
        SpriteIndex spriteIndex,
        ExportContext ctx,
        DoubleConsumer progressCallback
    ) throws IOException {
        boolean logRemap = VoxelBridgeLogger.isDebugEnabled(LogModule.UV_REMAP);
        if (logRemap) {
            VoxelBridgeLogger.info(LogModule.UV_REMAP, "[UVRemapper] Starting UV remapping...");
        }

        long totalQuads = spriteIndex.getTotalQuadCount();
        if (logRemap) {
            VoxelBridgeLogger.info(LogModule.UV_REMAP, String.format("[UVRemapper] Total quads to process: %d", totalQuads));
        }

        boolean atlasEnabled = UvRemapUtil.isAtlasEnabled();
        if (!atlasEnabled) {
            // No atlas mode, directly copy uvraw.bin to finaluv.bin
            if (logRemap) {
                VoxelBridgeLogger.info(LogModule.UV_REMAP, "[UVRemapper] Atlas disabled, copying uvraw.bin -> finaluv.bin");
            }
            java.nio.file.Files.copy(uvrawBin, finaluvBin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        try (FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
             FileChannel uvInChannel = FileChannel.open(uvrawBin, StandardOpenOption.READ);
             FileChannel uvOutChannel = FileChannel.open(finaluvBin,
                 StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE,
                 StandardOpenOption.TRUNCATE_EXISTING)) {

            // Allocate buffers
            int chunkBytes = CHUNK_SIZE_QUADS * BYTES_PER_QUAD_UV;
            ByteBuffer geometryBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE_QUADS * 140).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer uvInBuffer = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer uvOutBuffer = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);

            long processedQuads = 0;
            while (processedQuads < totalQuads) {
                int quadsThisChunk = (int) Math.min(CHUNK_SIZE_QUADS, totalQuads - processedQuads);

                // Read geometry chunk (to get spriteId)
                geometryBuffer.clear();
                geometryBuffer.limit(quadsThisChunk * 140);
                while (geometryBuffer.hasRemaining()) {
                    if (geometryChannel.read(geometryBuffer) < 0) break;
                }
                geometryBuffer.flip();

                // Read UV chunk
                uvInBuffer.clear();
                uvInBuffer.limit(quadsThisChunk * BYTES_PER_QUAD_UV);
                while (uvInBuffer.hasRemaining()) {
                    if (uvInChannel.read(uvInBuffer) < 0) break;
                }
                uvInBuffer.flip();

                // Remap UVs
                uvOutBuffer.clear();
                for (int i = 0; i < quadsThisChunk; i++) {
                    // Read spriteId from geometry (skip other fields)
                    geometryBuffer.position(i * 140 + 4); // Skip materialHash
                    int spriteId = geometryBuffer.getInt();
                    int overlaySpriteId = geometryBuffer.getInt();

                    String spriteKey = spriteIndex.getKey(spriteId);
                    String overlayKey = overlaySpriteId >= 0 ? spriteIndex.getKey(overlaySpriteId) : null;

                    // Read original UV from uvIn
                    uvInBuffer.position(i * BYTES_PER_QUAD_UV);
                    float[] uv0 = new float[8];
                    float[] uv1 = new float[8];
                    for (int j = 0; j < 8; j++) uv0[j] = uvInBuffer.getFloat();
                    for (int j = 0; j < 8; j++) uv1[j] = uvInBuffer.getFloat();

                    // Remap uv0
                    if (UvRemapUtil.shouldRemap(ctx, spriteKey)) {
                        for (int v = 0; v < 4; v++) {
                            float[] remapped = UvRemapUtil.remapUv(ctx, spriteKey, uv0[v * 2], uv0[v * 2 + 1]);
                            uv0[v * 2] = remapped[0];
                            uv0[v * 2 + 1] = remapped[1];
                        }
                    }

                    // Remap uv1 (overlay)
                    // IMPORTANT: In colormap mode, uv1 contains color map coordinates (LUT UV), not sprite texture UV
                    // Color map UVs should NOT be remapped - they already point to the correct position in the color LUT texture
                    boolean isColormapMode = UvRemapUtil.isColormapMode();
                    if (!isColormapMode && UvRemapUtil.shouldRemap(ctx, overlayKey)) {
                        boolean hasUV1 = false;
                        for (float f : uv1) if (f != 0) { hasUV1 = true; break; }
                        if (hasUV1) {
                            for (int v = 0; v < 4; v++) {
                                float[] remapped = UvRemapUtil.remapUv(ctx, overlayKey, uv1[v * 2], uv1[v * 2 + 1]);
                                uv1[v * 2] = remapped[0];
                                uv1[v * 2 + 1] = remapped[1];
                            }
                        }
                    }

                    // Write remapped UVs
                    for (float f : uv0) uvOutBuffer.putFloat(f);
                    for (float f : uv1) uvOutBuffer.putFloat(f);
                }

                // Write finaluv chunk
                uvOutBuffer.flip();
                while (uvOutBuffer.hasRemaining()) {
                    uvOutChannel.write(uvOutBuffer);
                }

                processedQuads += quadsThisChunk;

                // Progress logging (every 10% or last)
                if (totalQuads > 0) {
                    double progress = processedQuads * 100.0 / totalQuads;
                    if (logRemap && (processedQuads % Math.max(1, totalQuads / 10) == 0 || processedQuads == totalQuads)) {
                        VoxelBridgeLogger.info(LogModule.UV_REMAP, String.format("[UVRemapper] %.0f%% (%d/%d quads)",
                            progress, processedQuads, totalQuads));
                    }
                    if (progressCallback != null) {
                        progressCallback.accept(Math.min(1.0, processedQuads / (double) totalQuads));
                    }
                }
            }

            if (logRemap) {
                VoxelBridgeLogger.info(LogModule.UV_REMAP, String.format("[UVRemapper] Completed. Output: %.2f MB", uvOutChannel.size() / 1024.0 / 1024.0));
            }
        }
    }

}




