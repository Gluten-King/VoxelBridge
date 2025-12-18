package com.voxelbridge.util.pool;

import java.util.ArrayDeque;
import java.util.function.Supplier;

/**
 * Generic object pool to reduce GC pressure during export.
 * Reuses objects instead of creating new instances.
 *
 * @param <T> the type of objects to pool
 */
public final class ObjectPool<T> {
    private final ArrayDeque<T> free = new ArrayDeque<>();
    private final int maxSize;
    private final Supplier<T> factory;

    /**
     * Creates a new object pool.
     *
     * @param maxSize maximum number of pooled objects
     * @param factory factory function to create new objects when pool is empty
     */
    public ObjectPool(int maxSize, Supplier<T> factory) {
        this.maxSize = maxSize;
        this.factory = factory;
    }

    /**
     * Acquires an object from the pool, or creates a new one if pool is empty.
     *
     * @return an object instance
     */
    public T acquire() {
        T value = free.pollFirst();
        return (value != null) ? value : factory.get();
    }

    /**
     * Releases an object back to the pool for reuse.
     * If pool is full, the object is discarded.
     *
     * @param value the object to release
     */
    public void release(T value) {
        if (value == null || free.size() >= maxSize) return;
        free.addFirst(value);
    }

    /**
     * Gets the current number of available objects in the pool.
     *
     * @return pool size
     */
    public int size() {
        return free.size();
    }

    /**
     * Clears all pooled objects.
     */
    public void clear() {
        free.clear();
    }
}
