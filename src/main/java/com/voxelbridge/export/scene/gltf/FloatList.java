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

    float[] computeMin(int stride) {
        if (size == 0) {
            float[] result = new float[stride];
            Arrays.fill(result, 0);
            return result;
        }
        float[] min = new float[stride];
        Arrays.fill(min, Float.POSITIVE_INFINITY);
        for (int i = 0; i < size; i += stride) {
            for (int j = 0; j < stride; j++) {
                if (i + j < size && data[i + j] < min[j]) {
                    min[j] = data[i + j];
                }
            }
        }
        return min;
    }

    float[] computeMax(int stride) {
        if (size == 0) {
            float[] result = new float[stride];
            Arrays.fill(result, 0);
            return result;
        }
        float[] max = new float[stride];
        Arrays.fill(max, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < size; i += stride) {
            for (int j = 0; j < stride; j++) {
                if (i + j < size && data[i + j] > max[j]) {
                    max[j] = data[i + j];
                }
            }
        }
        return max;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }
}
