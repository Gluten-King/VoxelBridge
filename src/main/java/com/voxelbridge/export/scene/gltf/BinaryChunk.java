package com.voxelbridge.export.scene.gltf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes float/int arrays into a contiguous binary buffer with alignment.
 * This implementation streams directly to disk to avoid holding the whole
 * binary chunk in heap memory.
 */
final class BinaryChunk {
    // Larger direct buffer to reduce write syscalls during streaming writes
    private static final int DEFAULT_BUFFER_SIZE = 128 * 1024;

    private final FileChannel channel;
    private final ByteBuffer scratch;
    private long size = 0;

    BinaryChunk(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        this.scratch = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    }

    long size() {
        return size;
    }

    int writeFloatArray(float[] values) throws IOException {
        return writeFloatArray(values, values.length);
    }

    /**
     * OPTIMIZATION: Write float array with length limit (for direct array refs).
     * @param values The float array (may contain extra capacity)
     * @param length Actual number of floats to write
     */
    int writeFloatArray(float[] values, int length) throws IOException {
        int offset = align(4);
        for (int i = 0; i < length; i++) {
            writeInt(Float.floatToIntBits(values[i]));
        }
        return offset;
    }

    int writeIntArray(int[] values) throws IOException {
        return writeIntArray(values, values.length);
    }

    /**
     * OPTIMIZATION: Write int array with length limit (for direct array refs).
     * @param values The int array (may contain extra capacity)
     * @param length Actual number of ints to write
     */
    int writeIntArray(int[] values, int length) throws IOException {
        int offset = align(4);
        for (int i = 0; i < length; i++) {
            writeInt(values[i]);
        }
        return offset;
    }

    void close() throws IOException {
        flushBuffer();
        channel.close();
    }

    private int align(int alignment) throws IOException {
        int padding = (int) ((alignment - (size % alignment)) % alignment);
        if (padding > 0) {
            writeZeros(padding);
        }
        return (int) size;
    }

    private void writeInt(int value) throws IOException {
        ensureCapacity(4);
        scratch.putInt(value);
        size += 4;
    }

    private void writeZeros(int count) throws IOException {
        while (count > 0) {
            int chunk = Math.min(count, scratch.remaining());
            for (int i = 0; i < chunk; i++) {
                scratch.put((byte) 0);
            }
            size += chunk;
            count -= chunk;
            if (!scratch.hasRemaining()) {
                flushBuffer();
            }
        }
    }

    private void ensureCapacity(int needed) throws IOException {
        if (scratch.remaining() < needed) {
            flushBuffer();
        }
    }

    private void flushBuffer() throws IOException {
        scratch.flip();
        while (scratch.hasRemaining()) {
            channel.write(scratch);
        }
        scratch.clear();
    }
}
