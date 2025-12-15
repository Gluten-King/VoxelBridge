package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.util.ExportLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 流式几何写入器：将quad数据流式写入geometry.bin和uvraw.bin。
 * 文件格式：
 * - geometry.bin: 每个quad 140字节（materialHash[4] + spriteId[4] + overlayId[4] + doubleSided[1] + pad[3] + pos[48] + normal[12] + color[64]）
 * - uvraw.bin: 每个quad 64字节（uv0[32] + uv1[32]）
 */
final class StreamingGeometryWriter implements AutoCloseable {
    private static final int GEOMETRY_BUFFER_SIZE = 4 * 1024 * 1024; // 4MB
    private static final int UV_BUFFER_SIZE = 1 * 1024 * 1024;       // 1MB
    private static final int BYTES_PER_QUAD_GEOMETRY = 140;
    private static final int BYTES_PER_QUAD_UV = 64;

    private final FileChannel geometryChannel;
    private final FileChannel uvChannel;
    private final ByteBuffer geometryBuffer;
    private final ByteBuffer uvBuffer;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;

    private boolean closed = false;

    StreamingGeometryWriter(Path geometryBin, Path uvrawBin, SpriteIndex spriteIndex, GeometryIndex geometryIndex) throws IOException {
        this.spriteIndex = spriteIndex;
        this.geometryIndex = geometryIndex;

        // 创建FileChannel（追加模式）
        this.geometryChannel = FileChannel.open(geometryBin,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        this.uvChannel = FileChannel.open(uvrawBin,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // 创建DirectByteBuffer（减少内存拷贝）
        this.geometryBuffer = ByteBuffer.allocateDirect(GEOMETRY_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        this.uvBuffer = ByteBuffer.allocateDirect(UV_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);

        ExportLogger.log("[StreamingWriter] Initialized geometry writer");
        ExportLogger.log("[StreamingWriter] geometry.bin: " + geometryBin);
        ExportLogger.log("[StreamingWriter] uvraw.bin: " + uvrawBin);
    }

    /**
     * 写入一个quad（线程安全 - 由单个writer线程调用）
     */
    synchronized long writeQuad(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) throws IOException {
        if (closed) {
            throw new IllegalStateException("Writer已关闭");
        }

        // 获取sprite ID
        int spriteId = spriteIndex.getId(spriteKey);
        int overlaySpriteId = overlaySpriteKey != null ? spriteIndex.getId(overlaySpriteKey) : -1;

        // 获取当前quad offset
        long quadOffset = spriteIndex.nextQuadOffset();

        // 记录到索引
        spriteIndex.recordUsage(spriteKey, 0xFFFFFF, quadOffset); // 默认tint为白色
        geometryIndex.recordQuad(materialGroupKey, spriteKey, quadOffset, doubleSided);

        // 写入geometry.bin
        writeGeometryData(materialGroupKey, spriteId, overlaySpriteId, positions, normal, colors, doubleSided);

        // 写入uvraw.bin
        writeUVData(uv0, uv1);

        return quadOffset;
    }

    private void writeGeometryData(
        String materialGroupKey,
        int spriteId,
        int overlaySpriteId,
        float[] positions,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) throws IOException {
        // 确保缓冲区有足够空间
        if (geometryBuffer.remaining() < BYTES_PER_QUAD_GEOMETRY) {
            flushGeometry();
        }

        // 写入geometry数据（140字节）
        geometryBuffer.putInt(materialGroupKey.hashCode()); // 4字节
        geometryBuffer.putInt(spriteId);                     // 4字节
        geometryBuffer.putInt(overlaySpriteId);              // 4字节
        geometryBuffer.put((byte) (doubleSided ? 1 : 0));   // 1字节
        geometryBuffer.put((byte) 0);                        // padding 3字节
        geometryBuffer.put((byte) 0);
        geometryBuffer.put((byte) 0);

        // positions: 12 floats (48字节)
        for (int i = 0; i < 12; i++) {
            geometryBuffer.putFloat(positions[i]);
        }

        // normal: 3 floats (12字节)
        if (normal != null && normal.length >= 3) {
            for (int i = 0; i < 3; i++) {
                geometryBuffer.putFloat(normal[i]);
            }
        } else {
            // 默认法线
            geometryBuffer.putFloat(0f);
            geometryBuffer.putFloat(1f);
            geometryBuffer.putFloat(0f);
        }

        // colors: 16 floats (64字节)
        for (int i = 0; i < 16; i++) {
            geometryBuffer.putFloat(colors[i]);
        }
    }

    private void writeUVData(float[] uv0, float[] uv1) throws IOException {
        // 确保缓冲区有足够空间
        if (uvBuffer.remaining() < BYTES_PER_QUAD_UV) {
            flushUV();
        }

        // uv0: 8 floats (32字节)
        for (int i = 0; i < 8; i++) {
            uvBuffer.putFloat(uv0[i]);
        }

        // uv1: 8 floats (32字节)
        if (uv1 != null && uv1.length >= 8) {
            for (int i = 0; i < 8; i++) {
                uvBuffer.putFloat(uv1[i]);
            }
        } else {
            // 默认UV为0
            for (int i = 0; i < 8; i++) {
                uvBuffer.putFloat(0f);
            }
        }
    }

    private void flushGeometry() throws IOException {
        if (geometryBuffer.position() == 0) return;
        geometryBuffer.flip();
        while (geometryBuffer.hasRemaining()) {
            geometryChannel.write(geometryBuffer);
        }
        geometryBuffer.clear();
    }

    private void flushUV() throws IOException {
        if (uvBuffer.position() == 0) return;
        uvBuffer.flip();
        while (uvBuffer.hasRemaining()) {
            uvChannel.write(uvBuffer);
        }
        uvBuffer.clear();
    }

    /**
     * 完成写入，刷新缓冲区
     */
    void finalizeWrite() throws IOException {
        if (closed) return;

        flushGeometry();
        flushUV();

        long totalQuads = spriteIndex.getTotalQuadCount();
        ExportLogger.log(String.format("[StreamingWriter] Finalized. Total quads: %d", totalQuads));
        ExportLogger.log(String.format("[StreamingWriter] geometry.bin size: %.2f MB", geometryChannel.size() / 1024.0 / 1024.0));
        ExportLogger.log(String.format("[StreamingWriter] uvraw.bin size: %.2f MB", uvChannel.size() / 1024.0 / 1024.0));
    }

    SpriteIndex getSpriteIndex() {
        return spriteIndex;
    }

    GeometryIndex getGeometryIndex() {
        return geometryIndex;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        finalizeWrite();
        geometryChannel.close();
        uvChannel.close();

        ExportLogger.log("[StreamingWriter] Closed");
    }
}
