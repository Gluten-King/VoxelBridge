package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.ExportLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class VxbUvRemapper {
    private static final int BYTES_PER_LOOP = 8;
    private static final int CHUNK_BYTES = 1 << 20; // 1MB

    private VxbUvRemapper() {}

    static void remapUv(ExportContext ctx,
                        List<String> spriteKeys,
                        List<VxbSceneBuilder.SpriteSize> spriteSizes,
                        List<VxbSceneBuilder.MeshInfo> meshes,
                        Path uvRawPath,
                        Path uvPath) throws IOException {
        if (!Files.exists(uvRawPath)) {
            throw new IOException("uvraw file missing: " + uvRawPath);
        }
        Files.deleteIfExists(uvPath);

        boolean atlasEnabled = ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS;
        boolean colormapMode = ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP;
        int atlasSize = ExportRuntimeConfig.getAtlasSize().getSize();

        RemapParams[] params = buildParams(ctx, spriteKeys, atlasEnabled);

        try (FileChannel in = FileChannel.open(uvRawPath, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(uvPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (VxbSceneBuilder.MeshInfo mesh : meshes) {
                for (VxbSceneBuilder.SectionInfo section : mesh.sections) {
                    long loopCount = section.faceIndexCount;
                    long uvBytes = loopCount * BYTES_PER_LOOP;
                    remapSegment(in, out, section.uvOffset, uvBytes, params, spriteSizes, atlasSize, atlasEnabled, false, colormapMode);
                    remapSegment(in, out, section.uv1Offset, uvBytes, params, spriteSizes, atlasSize, atlasEnabled, true, colormapMode);
                }
            }
        }

        ExportLogger.log("[VXB] UV remap complete: " + uvPath.getFileName());
    }

    private static void remapSegment(FileChannel in,
                                     FileChannel out,
                                     long offset,
                                     long length,
                                     RemapParams[] params,
                                     List<VxbSceneBuilder.SpriteSize> spriteSizes,
                                     int atlasSize,
                                     boolean atlasEnabled,
                                     boolean isUv1,
                                     boolean colormapMode) throws IOException {
        if (length <= 0) return;

        ByteBuffer inBuf = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuf = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        long remaining = length;
        long pos = offset;

        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, CHUNK_BYTES);
            inBuf.clear();
            outBuf.clear();
            inBuf.limit(chunk);

            in.position(pos);
            readFully(in, inBuf);
            inBuf.flip();

            int loops = chunk / BYTES_PER_LOOP;
            for (int i = 0; i < loops; i++) {
                int u = inBuf.getShort() & 0xFFFF;
                int v = inBuf.getShort() & 0xFFFF;
                int spriteId = inBuf.getShort() & 0xFFFF;
                int pad = inBuf.getShort() & 0xFFFF;

                if (isUv1 && colormapMode) {
                    // Keep colormap UVs in normalized u16
                } else if (atlasEnabled && spriteId < params.length) {
                    RemapParams p = params[spriteId];
                    if (p != null) {
                        float uNorm;
                        float vNorm;
                        if (isUv1) {
                            uNorm = u / 65535f;
                            vNorm = v / 65535f;
                        } else {
                            VxbSceneBuilder.SpriteSize size = spriteSizes.get(spriteId);
                            uNorm = size.width > 0 ? u / (float) size.width : 0f;
                            vNorm = size.height > 0 ? v / (float) size.height : 0f;
                        }
                        float uu = p.baseU + uNorm * p.scaleU;
                        float vv = p.baseV + vNorm * p.scaleV;
                        u = quantizeUvAtlas(uu, atlasSize);
                        v = quantizeUvAtlas(vv, atlasSize);
                    } else if (isUv1 && !colormapMode) {
                        u = quantizeUvAtlas(u / 65535f, atlasSize);
                        v = quantizeUvAtlas(v / 65535f, atlasSize);
                    } else if (!isUv1) {
                        VxbSceneBuilder.SpriteSize size = spriteSizes.get(spriteId);
                        float uNorm = size.width > 0 ? u / (float) size.width : 0f;
                        float vNorm = size.height > 0 ? v / (float) size.height : 0f;
                        u = quantizeUvAtlas(uNorm, atlasSize);
                        v = quantizeUvAtlas(vNorm, atlasSize);
                    }
                }

                outBuf.putShort((short) u);
                outBuf.putShort((short) v);
                outBuf.putShort((short) spriteId);
                outBuf.putShort((short) pad);
            }

            outBuf.flip();
            out.position(pos);
            out.write(outBuf);

            remaining -= chunk;
            pos += chunk;
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
