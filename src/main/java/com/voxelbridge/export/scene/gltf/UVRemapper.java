package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.ExportLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * UV重映射器：顺序读取uvraw.bin，根据atlas重映射UV坐标，流式写入finaluv.bin。
 */
final class UVRemapper {
    private static final int CHUNK_SIZE_QUADS = 65536; // 64K quads per chunk
    private static final int BYTES_PER_QUAD_UV = 64;   // 每个quad的UV数据大小

    private UVRemapper() {}

    /**
     * 重映射UV坐标
     * @param geometryBin geometry.bin路径（用于读取spriteId）
     * @param uvrawBin uvraw.bin路径
     * @param finaluvBin 输出finaluv.bin路径
     * @param spriteIndex sprite索引
     * @param ctx 导出上下文
     */
    static void remapUVs(
        Path geometryBin,
        Path uvrawBin,
        Path finaluvBin,
        SpriteIndex spriteIndex,
        ExportContext ctx
    ) throws IOException {
        ExportLogger.log("[UVRemapper] Starting UV remapping...");

        long totalQuads = spriteIndex.getTotalQuadCount();
        ExportLogger.log(String.format("[UVRemapper] Total quads to process: %d", totalQuads));

        boolean atlasEnabled = ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS;
        if (!atlasEnabled) {
            // 无atlas模式，直接复制uvraw.bin到finaluv.bin
            ExportLogger.log("[UVRemapper] Atlas disabled, copying uvraw.bin -> finaluv.bin");
            java.nio.file.Files.copy(uvrawBin, finaluvBin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        try (FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
             FileChannel uvInChannel = FileChannel.open(uvrawBin, StandardOpenOption.READ);
             FileChannel uvOutChannel = FileChannel.open(finaluvBin,
                 StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE,
                 StandardOpenOption.TRUNCATE_EXISTING)) {

            // 分配缓冲区
            int chunkBytes = CHUNK_SIZE_QUADS * BYTES_PER_QUAD_UV;
            ByteBuffer geometryBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE_QUADS * 140).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer uvInBuffer = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer uvOutBuffer = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);

            long processedQuads = 0;
            while (processedQuads < totalQuads) {
                int quadsThisChunk = (int) Math.min(CHUNK_SIZE_QUADS, totalQuads - processedQuads);

                // 读取geometry chunk（获取spriteId）
                geometryBuffer.clear();
                geometryBuffer.limit(quadsThisChunk * 140);
                while (geometryBuffer.hasRemaining()) {
                    if (geometryChannel.read(geometryBuffer) < 0) break;
                }
                geometryBuffer.flip();

                // 读取UV chunk
                uvInBuffer.clear();
                uvInBuffer.limit(quadsThisChunk * BYTES_PER_QUAD_UV);
                while (uvInBuffer.hasRemaining()) {
                    if (uvInChannel.read(uvInBuffer) < 0) break;
                }
                uvInBuffer.flip();

                // 重映射UV
                uvOutBuffer.clear();
                for (int i = 0; i < quadsThisChunk; i++) {
                    // 从geometry读取spriteId（跳过其他字段）
                    geometryBuffer.position(i * 140 + 4); // 跳过materialHash
                    int spriteId = geometryBuffer.getInt();
                    int overlaySpriteId = geometryBuffer.getInt();

                    String spriteKey = spriteIndex.getKey(spriteId);
                    String overlayKey = overlaySpriteId >= 0 ? spriteIndex.getKey(overlaySpriteId) : null;

                    // 从uvIn读取原始UV
                    uvInBuffer.position(i * BYTES_PER_QUAD_UV);
                    float[] uv0 = new float[8];
                    float[] uv1 = new float[8];
                    for (int j = 0; j < 8; j++) uv0[j] = uvInBuffer.getFloat();
                    for (int j = 0; j < 8; j++) uv1[j] = uvInBuffer.getFloat();

                    // 重映射uv0
                    if (spriteKey != null && !isAnimated(ctx, spriteKey) && hasAtlasPlacement(ctx, spriteKey)) {
                        for (int v = 0; v < 4; v++) {
                            float[] remapped = TextureAtlasManager.remapUV(ctx, spriteKey, 0xFFFFFF, uv0[v * 2], uv0[v * 2 + 1]);
                            uv0[v * 2] = remapped[0];
                            uv0[v * 2 + 1] = remapped[1];
                        }
                    }

                    // 重映射uv1（overlay）
                    if (overlayKey != null && !isAnimated(ctx, overlayKey) && hasAtlasPlacement(ctx, overlayKey)) {
                        boolean hasUV1 = false;
                        for (float f : uv1) if (f != 0) { hasUV1 = true; break; }
                        if (hasUV1) {
                            for (int v = 0; v < 4; v++) {
                                float[] remapped = TextureAtlasManager.remapUV(ctx, overlayKey, 0xFFFFFF, uv1[v * 2], uv1[v * 2 + 1]);
                                uv1[v * 2] = remapped[0];
                                uv1[v * 2 + 1] = remapped[1];
                            }
                        }
                    }

                    // 写入重映射后的UV
                    for (float f : uv0) uvOutBuffer.putFloat(f);
                    for (float f : uv1) uvOutBuffer.putFloat(f);
                }

                // 写入finaluv chunk
                uvOutBuffer.flip();
                while (uvOutBuffer.hasRemaining()) {
                    uvOutChannel.write(uvOutBuffer);
                }

                processedQuads += quadsThisChunk;

                // 进度日志（每10%或最后）
                if (totalQuads > 0) {
                    double progress = processedQuads * 100.0 / totalQuads;
                    if (processedQuads % Math.max(1, totalQuads / 10) == 0 || processedQuads == totalQuads) {
                        ExportLogger.log(String.format("[UVRemapper] %.0f%% (%d/%d quads)",
                            progress, processedQuads, totalQuads));
                    }
                }
            }

            ExportLogger.log(String.format("[UVRemapper] Completed. Output: %.2f MB", uvOutChannel.size() / 1024.0 / 1024.0));
        }
    }

    private static boolean isAnimated(ExportContext ctx, String spriteKey) {
        return ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(spriteKey);
    }

    private static boolean hasAtlasPlacement(ExportContext ctx, String spriteKey) {
        return ctx.getAtlasBook().containsKey(spriteKey)
            || ctx.getBlockEntityAtlasPlacements().containsKey(spriteKey);
    }
}
