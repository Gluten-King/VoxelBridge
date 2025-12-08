package com.voxelbridge.export.texture;

import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Session-scoped texture repository used to avoid static cache leakage.
 * Supports both ResourceLocation-based keys and spriteKey-based keys for generated or virtual textures.
 */
public final class TextureRepository {
    private final Map<ResourceLocation, BufferedImage> locationCache = new ConcurrentHashMap<>();
    private final Map<String, ResourceLocation> keyToLocation = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> spriteCache = new ConcurrentHashMap<>();

    public BufferedImage get(ResourceLocation loc) {
        return locationCache.get(loc);
    }

    public BufferedImage getBySpriteKey(String spriteKey) {
        if (spriteKey == null) return null;
        ResourceLocation loc = keyToLocation.get(spriteKey);
        if (loc != null) {
            BufferedImage img = locationCache.get(loc);
            if (img != null) return img;
        }
        return spriteCache.get(spriteKey);
    }

    public boolean contains(ResourceLocation loc) {
        return locationCache.containsKey(loc);
    }

    /**
     * Put an image with both ResourceLocation and optional spriteKey mapping.
     * If loc is provided, it is stored in locationCache; spriteKey will map to loc.
     * If spriteKey is provided without loc, it will be stored in spriteCache (generated/virtual).
     */
    public BufferedImage put(ResourceLocation loc, String spriteKey, BufferedImage img) {
        if (img == null) return null;
        if (loc != null) {
            locationCache.put(loc, img);
            if (spriteKey != null) {
                keyToLocation.put(spriteKey, loc);
            }
        } else if (spriteKey != null) {
            spriteCache.put(spriteKey, img);
        }
        return img;
    }

    public BufferedImage computeIfAbsent(ResourceLocation loc, Function<ResourceLocation, BufferedImage> loader) {
        return locationCache.computeIfAbsent(loc, loader);
    }

    public void register(String spriteKey, ResourceLocation loc) {
        if (spriteKey != null && loc != null) {
            keyToLocation.put(spriteKey, loc);
        }
    }

    public ResourceLocation getRegisteredLocation(String spriteKey) {
        return keyToLocation.get(spriteKey);
    }

    public Map<String, ResourceLocation> getRegisteredTextures() {
        return keyToLocation;
    }

    /** Store a generated texture keyed only by spriteKey. */
    public void putGenerated(String spriteKey, BufferedImage image) {
        if (spriteKey != null && image != null) {
            spriteCache.put(spriteKey, image);
        }
    }

    public Set<String> getSpriteKeys() {
        return spriteCache.keySet();
    }

    /**
     * Exposes the sprite-keyed cache (generated/virtual textures).
     */
    public Map<String, BufferedImage> getSpriteCache() {
        return spriteCache;
    }

    public void clear() {
        locationCache.clear();
        keyToLocation.clear();
        spriteCache.clear();
    }
}
