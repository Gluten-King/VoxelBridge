package com.voxelbridge.export.scene.gltf;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprite索引：管理sprite key到ID的映射，以及sprite使用统计信息。
 * 用于：
 * 1. 压缩存储（在geometry.bin中使用int ID而非字符串）
 * 2. 图集生成（提供所有使用的sprite和tint信息）
 */
final class SpriteIndex {
    // Sprite key ↔ ID 双向映射
    private final Object2IntMap<String> spriteToId = new Object2IntOpenHashMap<>();
    private final List<String> idToSprite = new ArrayList<>();

    // 每个sprite的使用信息（用于atlas生成）
    private final Map<String, SpriteUsageInfo> spriteUsage = new ConcurrentHashMap<>();

    // 全局quad计数器
    private long nextQuadOffset = 0;

    /**
     * Sprite使用信息
     * @param tintColors 该sprite使用的所有tint颜色
     * @param firstQuadOffset 第一个使用该sprite的quad在geometry.bin中的偏移
     * @param quadCount 使用该sprite的quad总数
     */
    record SpriteUsageInfo(
        Set<Integer> tintColors,
        long firstQuadOffset,
        int quadCount
    ) {}

    /**
     * 获取或注册sprite ID（线程安全）
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
     * 根据ID获取sprite key
     */
    synchronized String getKey(int id) {
        if (id < 0 || id >= idToSprite.size()) {
            return null;
        }
        return idToSprite.get(id);
    }

    /**
     * 记录sprite使用（quad级别）
     * @param spriteKey sprite标识
     * @param tint tint颜色值
     * @param quadOffset 该quad在geometry.bin中的偏移
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
     * 获取所有已注册的sprite keys
     */
    synchronized List<String> getAllKeys() {
        return new ArrayList<>(idToSprite);
    }

    /**
     * 获取sprite数量
     */
    synchronized int size() {
        return idToSprite.size();
    }

    /**
     * 获取sprite使用信息
     */
    SpriteUsageInfo getUsageInfo(String spriteKey) {
        return spriteUsage.get(spriteKey);
    }

    /**
     * 获取所有sprite使用信息
     */
    Map<String, SpriteUsageInfo> getAllUsageInfo() {
        return new HashMap<>(spriteUsage);
    }

    /**
     * 递增并返回当前quad offset
     */
    synchronized long nextQuadOffset() {
        return nextQuadOffset++;
    }

    /**
     * 获取当前总quad数
     */
    synchronized long getTotalQuadCount() {
        return nextQuadOffset;
    }
}
