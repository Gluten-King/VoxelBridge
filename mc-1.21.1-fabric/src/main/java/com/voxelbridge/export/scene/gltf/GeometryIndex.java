package com.voxelbridge.export.scene.gltf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometry index: tracks per-material metadata for geometry.bin and uvraw.bin.
 */
final class GeometryIndex {

    // Material metadata mapping.
    private final Map<String, MaterialChunk> materials = new ConcurrentHashMap<>();

    /**
     * Page info: location and size of a contiguous block of quads in the file.
     */
    record PageInfo(long byteOffset, int quadCount) {}

    /**
     * Per-material geometry metadata.
     */
    record MaterialChunk(
        String materialGroupKey,
        List<PageInfo> pages,
        boolean doubleSided,
        Set<String> usedSprites
    ) {
        int quadCount() {
            int sum = 0;
            for (PageInfo p : pages) sum += p.quadCount();
            return sum;
        }
    }

    /**
     * Record a flushed page for a material.
     */
    void recordPage(String materialGroupKey, Set<String> spriteKeys, long byteOffset, int quadCount, boolean doubleSided) {
        materials.compute(materialGroupKey, (k, chunk) -> {
            PageInfo page = new PageInfo(byteOffset, quadCount);
            if (chunk == null) {
                Set<String> sprites = ConcurrentHashMap.newKeySet();
                sprites.addAll(spriteKeys);
                List<PageInfo> pages = new ArrayList<>();
                pages.add(page);
                return new MaterialChunk(
                    materialGroupKey,
                    pages,
                    doubleSided,
                    sprites
                );
            } else {
                chunk.usedSprites().addAll(spriteKeys);
                chunk.pages().add(page);
                if (doubleSided && !chunk.doubleSided()) {
                    return new MaterialChunk(
                        chunk.materialGroupKey(),
                        chunk.pages(),
                        true,
                        chunk.usedSprites()
                    );
                }
                return chunk;
            }
        });
    }

    /**
     * Get material metadata by key.
     */
    MaterialChunk getMaterial(String materialGroupKey) {
        return materials.get(materialGroupKey);
    }

    /**
     * Get all material keys in sorted order.
     */
    List<String> getAllMaterialKeys() {
        List<String> keys = new ArrayList<>(materials.keySet());
        Collections.sort(keys);
        return keys;
    }

    /**
     * Get material count.
     */
    int size() {
        return materials.size();
    }

    /**
     * Get total quad count across materials.
     */
    long getTotalQuadCount() {
        return materials.values().stream()
            .mapToLong(MaterialChunk::quadCount)
            .sum();
    }

    /**
     * Get a snapshot of all material metadata.
     */
    Map<String, MaterialChunk> getAllMaterials() {
        return new HashMap<>(materials);
    }
}
