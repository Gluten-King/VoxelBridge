package com.voxelbridge.export.scene.gltf;

import java.util.Arrays;

/**
 * Minimal growable float array used during mesh accumulation.
 */
final class FloatList {
    private static final int DEFAULT_CAPACITY = 256;
    private float[] data;
    private int size = 0;

    FloatList() {
        this(DEFAULT_CAPACITY);
    }

    FloatList(int initialCapacity) {
        int capacity = initialCapacity > 0 ? initialCapacity : DEFAULT_CAPACITY;
        this.data = new float[capacity];
    }

    void add(float value) {
        ensure(size + 1);
        data[size++] = value;
    }

    void addAll(float[] values) {
        ensure(size + values.length);
        for (float v : values) {
            data[size++] = v;
        }
    }

    int size() {
        return size;
    }

    float[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * OPTIMIZATION: Get direct reference to internal array (avoiding copy).
     * WARNING: Only use when you need to write to buffer immediately and won't modify.
     * Caller must use size() to know actual data length.
     */
    float[] getArrayDirect() {
        return data;
    }

    float get(int idx) {
        return data[idx];
    }

    private void ensure(int needed) {
        if (needed <= data.length) return;
        int newLength = data.length;
        while (newLength < needed) {
            newLength <<= 1; // grow until it actually fits the requested size
        }
        data = Arrays.copyOf(data, newLength);
    }

    float[] computeMin() {
        if (size == 0) return new float[]{0, 0, 0};
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        for (int i = 0; i < size; i += 3) {
            float x = data[i];
            float y = data[i + 1];
            float z = data[i + 2];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
        }
        return new float[]{minX, minY, minZ};
    }

    float[] computeMax() {
        if (size == 0) return new float[]{0, 0, 0};
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i += 3) {
            float x = data[i];
            float y = data[i + 1];
            float z = data[i + 2];
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        return new float[]{maxX, maxY, maxZ};
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }
}
