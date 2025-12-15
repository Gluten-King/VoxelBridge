package com.voxelbridge.export.scene.gltf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 几何索引：维护每个material在geometry.bin和uvraw.bin中的元数据。
 * 用于：
 * 1. glTF组装时定位material的几何数据
 * 2. UV重映射时查找对应的sprite信息
 */
final class GeometryIndex {
    // Material元数据映射
    private final Map<String, MaterialChunk> materials = new ConcurrentHashMap<>();

    /**
     * Material几何块元数据
     * @param materialGroupKey material标识
     * @param quadOffsets     该material包含的所有quad的全局偏移集合（以quad为单位）
     * @param doubleSided     是否双面
     * @param usedSprites     该material使用的所有sprite keys
     */
    record MaterialChunk(
        String materialGroupKey,
        List<Long> quadOffsets,
        boolean doubleSided,
        Set<String> usedSprites
    ) {
        int quadCount() {
            return quadOffsets.size();
        }
    }

    /**
     * 记录material的quad写入
     * @param materialGroupKey material标识
     * @param spriteKey 使用的sprite
     * @param quadOffset quad在文件中的偏移
     * @param doubleSided 是否双面
     */
    void recordQuad(String materialGroupKey, String spriteKey, long quadOffset, boolean doubleSided) {
        materials.compute(materialGroupKey, (k, chunk) -> {
            if (chunk == null) {
                Set<String> sprites = ConcurrentHashMap.newKeySet();
                sprites.add(spriteKey);
                List<Long> offsets = new ArrayList<>();
                offsets.add(quadOffset);
                return new MaterialChunk(
                    materialGroupKey,
                    offsets,
                    doubleSided,
                    sprites
                );
            } else {
                chunk.usedSprites().add(spriteKey);
                chunk.quadOffsets().add(quadOffset);
                if (doubleSided && !chunk.doubleSided()) {
                    return new MaterialChunk(
                        chunk.materialGroupKey(),
                        chunk.quadOffsets(),
                        true,
                        chunk.usedSprites()
                    );
                }
                return chunk;
            }
        });
    }

    /**
     * 获取material元数据
     */
    MaterialChunk getMaterial(String materialGroupKey) {
        return materials.get(materialGroupKey);
    }

    /**
     * 获取所有material keys（排序）
     */
    List<String> getAllMaterialKeys() {
        List<String> keys = new ArrayList<>(materials.keySet());
        Collections.sort(keys);
        return keys;
    }

    /**
     * 获取material数量
     */
    int size() {
        return materials.size();
    }

    /**
     * 获取总quad数
     */
    long getTotalQuadCount() {
        return materials.values().stream()
            .mapToLong(MaterialChunk::quadCount)
            .sum();
    }

    /**
     * 获取所有material元数据
     */
    Map<String, MaterialChunk> getAllMaterials() {
        return new HashMap<>(materials);
    }
}
