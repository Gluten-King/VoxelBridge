package com.voxelbridge.export.scene.gltf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometry index: tracks per-material metadata for geometry.bin and uvraw.bin.
 */
final class GeometryIndex {

    // Material metadata mapping.
    // Material metadata mapping.
    private final Map<String, MaterialChunk> materials = new ConcurrentHashMap<>();

    /**
     * Per-material geometry metadata.
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
     * Record a quad write for a material.
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
