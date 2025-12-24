package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.UvRemapUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.DoubleConsumer;

final class VxbUvRemapper {
    private static final int BYTES_PER_LOOP = 12;
    private static final int CHUNK_BYTES = 2 << 20; // 2MB

    private VxbUvRemapper() {}

    static void remapUv(ExportContext ctx,
                        List<String> spriteKeys,
                        List<VxbSceneBuilder.SpriteSize> spriteSizes,
                        List<VxbSceneBuilder.MeshInfo> meshes,
                        Path uvRawPath,
                        Path uvPath,
                        DoubleConsumer progressCallback) throws IOException {
        if (!Files.exists(uvRawPath)) {
            throw new IOException("uvraw file missing: " + uvRawPath);
        }
        Files.deleteIfExists(uvPath);

        boolean atlasEnabled = UvRemapUtil.isAtlasEnabled();
        boolean colormapMode = UvRemapUtil.isColormapMode();
        int atlasSize = ExportRuntimeConfig.getAtlasSize().getSize();

        RemapParams[] params = buildParams(ctx, spriteKeys, atlasEnabled);
        long[] processedBytes = {0L};
        long[] nextLogBytes = {64L << 20}; // 64MB
        long tRemap = com.voxelbridge.util.debug.VoxelBridgeLogger.now();
        long[] readNanos = {0L};
        long[] processNanos = {0L};
        long[] writeNanos = {0L};
        // Reuse buffers across sections to avoid per-section direct buffer allocation overhead
        ByteBuffer inBuf = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuf = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        long sectionCount = 0;
        long totalLoops = 0;
        for (VxbSceneBuilder.MeshInfo mesh : meshes) {
            sectionCount += mesh.sections.size();
            for (VxbSceneBuilder.SectionInfo section : mesh.sections) {
                totalLoops += section.faceIndexCount;
            }
        }
        long totalBytes = totalLoops * BYTES_PER_LOOP * 2L; // uv + uv1
        long uvRawSize = Files.size(uvRawPath);
        String startMsg = String.format("[VXB] UV remap start: sections=%d, loops=%d, est_bytes=%d, uvraw=%d bytes",
            sectionCount, totalLoops, totalBytes, uvRawSize);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.UV_REMAP)) {
            VoxelBridgeLogger.info(LogModule.UV_REMAP, startMsg);
            VoxelBridgeLogger.logInfo(startMsg);
        }

        try (FileChannel in = FileChannel.open(uvRawPath, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(uvPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (VxbSceneBuilder.MeshInfo mesh : meshes) {
                for (VxbSceneBuilder.SectionInfo section : mesh.sections) {
                    long loopCount = section.faceIndexCount;
                    long uvBytes = loopCount * BYTES_PER_LOOP;
                    remapSegment(in, out, inBuf, outBuf, section.uvOffset, uvBytes, params, spriteKeys, spriteSizes, ctx, atlasSize, atlasEnabled, false, colormapMode, processedBytes, nextLogBytes, tRemap, uvPath, readNanos, processNanos, writeNanos, totalBytes, progressCallback);
                    remapSegment(in, out, inBuf, outBuf, section.uv1Offset, uvBytes, params, spriteKeys, spriteSizes, ctx, atlasSize, atlasEnabled, true, colormapMode, processedBytes, nextLogBytes, tRemap, uvPath, readNanos, processNanos, writeNanos, totalBytes, progressCallback);
                }
            }
        }

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.UV_REMAP)) {
            VoxelBridgeLogger.info(LogModule.UV_REMAP, "[VXB] UV remap complete: " + uvPath.getFileName());
        }
        VoxelBridgeLogger.duration("vxb_uv_remap_read", readNanos[0]);
        VoxelBridgeLogger.duration("vxb_uv_remap_process", processNanos[0]);
        VoxelBridgeLogger.duration("vxb_uv_remap_write", writeNanos[0]);
    }

    private static void remapSegment(FileChannel in,
                                     FileChannel out,
                                     ByteBuffer inBuf,
                                     ByteBuffer outBuf,
                                     long offset,
                                     long length,
                                     RemapParams[] params,
                                     List<String> spriteKeys,
                                     List<VxbSceneBuilder.SpriteSize> spriteSizes,
                                     ExportContext ctx,
                                     int atlasSize,
                                     boolean atlasEnabled,
                                     boolean isUv1,
                                     boolean colormapMode,
                                     long[] processedBytes,
                                     long[] nextLogBytes,
                                     long tRemapStart,
                                     Path uvPath,
                                     long[] readNanos,
                                     long[] processNanos,
                                     long[] writeNanos,
                                     long totalBytes,
                                     DoubleConsumer progressCallback) throws IOException {
        if (length <= 0) return;

        long remaining = length;
        long pos = offset;

        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, CHUNK_BYTES);
            inBuf.clear();
            outBuf.clear();
            inBuf.limit(chunk);

            in.position(pos);
            long tRead = com.voxelbridge.util.debug.VoxelBridgeLogger.now();
            readFully(in, inBuf);
            readNanos[0] += com.voxelbridge.util.debug.VoxelBridgeLogger.elapsedSince(tRead);
            inBuf.flip();

            long tProcess = com.voxelbridge.util.debug.VoxelBridgeLogger.now();
            int loops = chunk / BYTES_PER_LOOP;
            for (int i = 0; i < loops; i++) {
                float u = inBuf.getFloat();
                float v = inBuf.getFloat();
                int spriteId = inBuf.getShort() & 0xFFFF;
                int pad = inBuf.getShort() & 0xFFFF;

                if (isUv1 && colormapMode) {
                    // Keep colormap UVs normalized (float), but flip V axis to match texture coordinates
                    v = 1.0f - v;
                } else if (isUv1 && !colormapMode && !atlasEnabled) {
                    // No atlas: keep UV1 in atlas-size space for compatibility
                    u = u * atlasSize;
                    v = v * atlasSize;
                } else if (atlasEnabled && spriteId < spriteKeys.size()) {
                    String spriteKey = spriteKeys.get(spriteId);
                    if (spriteKey != null) {
                        if (isUv1 && !colormapMode) {
                            if (UvRemapUtil.shouldRemap(ctx, spriteKey)) {
                                float[] remapped = UvRemapUtil.remapUv(ctx, spriteKey, u, v);
                                u = remapped[0] * atlasSize;
                                v = remapped[1] * atlasSize;
                            } else {
                                u = u * atlasSize;
                                v = v * atlasSize;
                            }
                        } else if (!isUv1) {
                            RemapParams p = params[spriteId];
                            if (p != null) {
                                VxbSceneBuilder.SpriteSize size = spriteSizes.get(spriteId);
                                float uNorm = size.width > 0 ? u / (float) size.width : 0f;
                                float vNorm = size.height > 0 ? v / (float) size.height : 0f;
                                float uu = p.baseU + uNorm * p.scaleU;
                                float vv = p.baseV + vNorm * p.scaleV;
                                u = uu * atlasSize;
                                v = vv * atlasSize;
                            }
                        }
                    }
                }

                outBuf.putFloat(u);
                outBuf.putFloat(v);
                outBuf.putShort((short) spriteId);
                outBuf.putShort((short) pad);
            }
            processNanos[0] += com.voxelbridge.util.debug.VoxelBridgeLogger.elapsedSince(tProcess);

            outBuf.flip();
            out.position(pos);
            long tWrite = com.voxelbridge.util.debug.VoxelBridgeLogger.now();
            out.write(outBuf);
            writeNanos[0] += com.voxelbridge.util.debug.VoxelBridgeLogger.elapsedSince(tWrite);

            remaining -= chunk;
            pos += chunk;

            processedBytes[0] += chunk;
            if (processedBytes[0] >= nextLogBytes[0]) {
                nextLogBytes[0] += 64L << 20; // advance next log threshold
                double mb = processedBytes[0] / 1024.0 / 1024.0;
                double elapsedSec = com.voxelbridge.util.debug.VoxelBridgeLogger.elapsedSince(tRemapStart) / 1_000_000_000.0;
                String msg = String.format("[VXB] UV remap progress %.1f MB for %s (elapsed %.3f s)",
                    mb, uvPath.getFileName(), elapsedSec);
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.UV_REMAP)) {
                    VoxelBridgeLogger.info(LogModule.UV_REMAP, msg);
                    VoxelBridgeLogger.logInfo(msg);
                }
            }
            if (progressCallback != null && totalBytes > 0) {
                double frac = Math.min(1.0, processedBytes[0] / (double) totalBytes);
                progressCallback.accept(frac);
            }
        }
    }

    private static RemapParams[] buildParams(ExportContext ctx,
                                            List<String> spriteKeys,
                                            boolean atlasEnabled) {
        RemapParams[] params = new RemapParams[spriteKeys.size()];
        if (!atlasEnabled) {
            return params;
        }

        for (int i = 0; i < spriteKeys.size(); i++) {
            String spriteKey = spriteKeys.get(i);
            if (!UvRemapUtil.shouldRemap(ctx, spriteKey)) {
                continue;
            }
            ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
            if (atlas == null || atlas.texH <= 0 || atlas.placements.isEmpty()) {
                continue;
            }
            ExportContext.TexturePlacement placement = atlas.placements.getOrDefault(0,
                atlas.placements.values().stream().findFirst().orElse(null));
            if (placement == null) {
                continue;
            }
            float texSize = atlas.texH;
            float baseU = placement.tileU() + (placement.x() / texSize);
            float scaleU = placement.w() / texSize;
            float baseV = placement.tileV() + 1f - (placement.y() / texSize);
            float scaleV = -placement.h() / texSize;
            params[i] = new RemapParams(baseU, scaleU, baseV, scaleV);
        }
        return params;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Unexpected end of uvraw channel");
            }
        }
    }

    private static int quantizeUvAtlas(float uv, int atlasSize) {
        if (atlasSize <= 0) return 0;
        int value = Math.round(uv * atlasSize);
        if (value < 0) return 0;
        if (value > 65535) return 65535;
        return value;
    }

    private record RemapParams(float baseU, float scaleU, float baseV, float scaleV) {}
}




