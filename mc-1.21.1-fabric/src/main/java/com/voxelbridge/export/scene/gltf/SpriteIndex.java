package com.voxelbridge.export.scene.gltf;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprite index: maps sprite keys to IDs and tracks usage stats.
 */
final class SpriteIndex {

    // Sprite key <-> ID bidirectional mapping.
    private final Object2IntMap<String> spriteToId = new Object2IntOpenHashMap<>();
    private final List<String> idToSprite = new ArrayList<>();

    // Per-sprite usage info (for atlas generation).
    // Per-sprite usage info (for atlas generation).
    private final Map<String, SpriteUsageInfo> spriteUsage = new ConcurrentHashMap<>();

    // Global quad offset counter.
    // Global quad offset counter.
    private long nextQuadOffset = 0;

    /**
     * Sprite usage info.
     */
    record SpriteUsageInfo(
        Set<Integer> tintColors,
        long firstQuadOffset,
        int quadCount
    ) {}

    /**
     * Get or register a sprite ID (thread-safe).
     */
    synchronized int getId(String spriteKey) {
        if (spriteToId.containsKey(spriteKey)) {
            return spriteToId.getInt(spriteKey);
        }
        int id = idToSprite.size();
        idToSprite.add(spriteKey);
        spriteToId.put(spriteKey, id);
        return id;
    }

    /**
     * Get a sprite key by ID.
     */
    synchronized String getKey(int id) {
        if (id < 0 || id >= idToSprite.size()) {
            return null;
        }
        return idToSprite.get(id);
    }

    /**
     * Record sprite usage at quad granularity.
     */
    void recordUsage(String spriteKey, int tint, long quadOffset) {
        spriteUsage.compute(spriteKey, (k, info) -> {
            if (info == null) {
                Set<Integer> tints = ConcurrentHashMap.newKeySet();
                tints.add(tint);
                return new SpriteUsageInfo(tints, quadOffset, 1);
            } else {
                info.tintColors.add(tint);
                return new SpriteUsageInfo(
                    info.tintColors,
                    info.firstQuadOffset,
                    info.quadCount + 1
                );
            }
        });
    }

    /**
     * Get all registered sprite keys.
     * OPTIMIZATION: Returns unmodifiable view instead of copying to avoid O(n) allocation.
     */
    synchronized List<String> getAllKeys() {
        return Collections.unmodifiableList(idToSprite);
    }

    /**
     * Get sprite count.
     */
    synchronized int size() {
        return idToSprite.size();
    }

    /**
     * Get usage info for a sprite.
     */
    SpriteUsageInfo getUsageInfo(String spriteKey) {
        return spriteUsage.get(spriteKey);
    }

    /**
     * Get a snapshot of all sprite usage info.
     */
    Map<String, SpriteUsageInfo> getAllUsageInfo() {
        return new HashMap<>(spriteUsage);
    }

    /**
     * Increment and return the current quad offset.
     */
    synchronized long nextQuadOffset() {
        return nextQuadOffset++;
    }

    /**
     * Get total quad count.
     */
    synchronized long getTotalQuadCount() {
        return nextQuadOffset;
    }
}
