package com.voxelbridge.export.scene.gltf;

import java.util.Arrays;

/**
 * Minimal growable int array used during mesh accumulation.
 */
final class IntList {
    private static final int DEFAULT_CAPACITY = 256;
    private int[] data;
    private int size = 0;

    IntList() {
        this(DEFAULT_CAPACITY);
    }

    IntList(int initialCapacity) {
        int capacity = initialCapacity > 0 ? initialCapacity : DEFAULT_CAPACITY;
        this.data = new int[capacity];
    }

    void add(int value) {
        ensure(size + 1);
        data[size++] = value;
    }

    int size() {
        return size;
    }

    int[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * OPTIMIZATION: Get direct reference to internal array (avoiding copy).
     * WARNING: Only use when you need to write to buffer immediately and won't modify.
     * Caller must use size() to know actual data length.
     */
    int[] getArrayDirect() {
        return data;
    }

    private void ensure(int needed) {
        if (needed <= data.length) return;
        data = Arrays.copyOf(data, data.length * 2);
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }
}
