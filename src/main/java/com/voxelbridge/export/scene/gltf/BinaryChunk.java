package com.voxelbridge.export.scene.gltf;

import java.io.ByteArrayOutputStream;

/**
 * Writes float/int arrays into a contiguous binary buffer with alignment.
 */
final class BinaryChunk {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    int size() {
        return out.size();
    }

    int writeFloatArray(float[] values) {
        int offset = align(4);
        for (float v : values) {
            writeInt(Float.floatToIntBits(v));
        }
        return offset;
    }

    int writeIntArray(int[] values) {
        int offset = align(4);
        for (int v : values) {
            writeInt(v);
        }
        return offset;
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private int align(int alignment) {
        int padding = (alignment - (out.size() % alignment)) % alignment;
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.size();
    }

    private void writeInt(int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
