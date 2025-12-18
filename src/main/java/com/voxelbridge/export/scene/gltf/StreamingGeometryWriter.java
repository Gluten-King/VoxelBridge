package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.util.debug.ExportLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streaming geometry writer: Streams quad data to geometry.bin and uvraw.bin.
 * File format:
 * - geometry.bin: 140 bytes per quad (materialHash[4] + spriteId[4] + overlayId[4] + doubleSided[1] + pad[3] + pos[48] + normal[12] + color[64])
 * - uvraw.bin: 64 bytes per quad (uv0[32] + uv1[32])
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

        // Create FileChannel (truncate mode)
        this.geometryChannel = FileChannel.open(geometryBin,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        this.uvChannel = FileChannel.open(uvrawBin,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // Create DirectByteBuffer (reduce memory copying)
        this.geometryBuffer = ByteBuffer.allocateDirect(GEOMETRY_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
        this.uvBuffer = ByteBuffer.allocateDirect(UV_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);

        ExportLogger.log("[StreamingWriter] Initialized geometry writer");
        ExportLogger.log("[StreamingWriter] geometry.bin: " + geometryBin);
        ExportLogger.log("[StreamingWriter] uvraw.bin: " + uvrawBin);
    }

    /**
     * Writes a single quad (thread-safe - called by single writer thread)
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
            throw new IllegalStateException("Writer is closed");
        }

        // Get sprite IDs
        int spriteId = spriteIndex.getId(spriteKey);
        int overlaySpriteId = overlaySpriteKey != null ? spriteIndex.getId(overlaySpriteKey) : -1;

        // Get current quad offset
        long quadOffset = spriteIndex.nextQuadOffset();

        // Record to index
        spriteIndex.recordUsage(spriteKey, 0xFFFFFF, quadOffset); // Default tint is white
        geometryIndex.recordQuad(materialGroupKey, spriteKey, quadOffset, doubleSided);

        // Write to geometry.bin
        writeGeometryData(materialGroupKey, spriteId, overlaySpriteId, positions, normal, colors, doubleSided);

        // Write to uvraw.bin
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
        // Ensure buffer has enough space
        if (geometryBuffer.remaining() < BYTES_PER_QUAD_GEOMETRY) {
            flushGeometry();
        }

        // Write geometry data (140 bytes)
        geometryBuffer.putInt(materialGroupKey.hashCode()); // 4 bytes
        geometryBuffer.putInt(spriteId);                     // 4 bytes
        geometryBuffer.putInt(overlaySpriteId);              // 4 bytes
        geometryBuffer.put((byte) (doubleSided ? 1 : 0));   // 1 byte
        geometryBuffer.put((byte) 0);                        // padding 3 bytes
        geometryBuffer.put((byte) 0);
        geometryBuffer.put((byte) 0);

        // positions: 12 floats (48 bytes)
        for (int i = 0; i < 12; i++) {
            geometryBuffer.putFloat(positions[i]);
        }

        // normal: 3 floats (12 bytes)
        if (normal != null && normal.length >= 3) {
            for (int i = 0; i < 3; i++) {
                geometryBuffer.putFloat(normal[i]);
            }
        } else {
            // Default normal (pointing up)
            geometryBuffer.putFloat(0f);
            geometryBuffer.putFloat(1f);
            geometryBuffer.putFloat(0f);
        }

        // colors: 16 floats (64 bytes)
        for (int i = 0; i < 16; i++) {
            geometryBuffer.putFloat(colors[i]);
        }
    }

    private void writeUVData(float[] uv0, float[] uv1) throws IOException {
        // Ensure buffer has enough space
        if (uvBuffer.remaining() < BYTES_PER_QUAD_UV) {
            flushUV();
        }

        // uv0: 8 floats (32 bytes)
        for (int i = 0; i < 8; i++) {
            uvBuffer.putFloat(uv0[i]);
        }

        // uv1: 8 floats (32 bytes)
        if (uv1 != null && uv1.length >= 8) {
            for (int i = 0; i < 8; i++) {
                uvBuffer.putFloat(uv1[i]);
            }
        } else {
            // Default UV is 0
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
     * Finalizes the write and flushes buffers
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
