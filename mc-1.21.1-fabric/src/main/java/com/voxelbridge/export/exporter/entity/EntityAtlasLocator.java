package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.platform.client.ClientAccess;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Finds the sprite inside an atlas that contains a given UV coordinate.
 */
final class EntityAtlasLocator implements AtlasLocator {
    private final ClientAccess clientAccess;

    EntityAtlasLocator(ClientAccess clientAccess) {
        this.clientAccess = clientAccess;
    }

    @Override
    public Sprite find(Identifier atlasLocation, float u, float v) {
        if (atlasLocation == null) {
            return null;
        }
        var tex = clientAccess.getTextureManager().getTexture(atlasLocation);
        if (!(tex instanceof SpriteAtlasTexture atlas)) {
            return null;
        }
        Map<Identifier, Sprite> sprites = getSprites(atlas);
        if (sprites == null) {
            return null;
        }
        for (Sprite sprite : sprites.values()) {
            if (contains(sprite, u, v)) {
                return sprite;
            }
        }
        return null;
    }

    private static boolean contains(Sprite sprite, float u, float v) {
        return u >= sprite.getMinU() && u <= sprite.getMaxU()
            && v >= sprite.getMinV() && v <= sprite.getMaxV();
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, Sprite> getSprites(SpriteAtlasTexture atlas) {
        Map<Identifier, Sprite> fromMethod = getSpritesViaMethod(atlas);
        if (fromMethod != null) {
            return fromMethod;
        }
        Map<Identifier, Sprite> fromField = getSpritesFromField(atlas, "sprites");
        if (fromField != null) {
            return fromField;
        }
        Map<Identifier, Sprite> scanned = scanForSpritesMap(atlas);
        if (scanned != null) {
            return scanned;
        }
        return null;
    }

    private static Map<Identifier, Sprite> getSpritesViaMethod(SpriteAtlasTexture atlas) {
        for (String name : new String[] {"getSprites", "getSpriteMap"}) {
            try {
                Method method = SpriteAtlasTexture.class.getDeclaredMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(atlas);
                Map<Identifier, Sprite> map = extractSpriteMap(value);
                if (map != null) {
                    return map;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, Sprite> getSpritesFromField(SpriteAtlasTexture atlas, String name) {
        try {
            Field field = SpriteAtlasTexture.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(atlas);
            return extractSpriteMap(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, Sprite> scanForSpritesMap(SpriteAtlasTexture atlas) {
        for (Field field : SpriteAtlasTexture.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(atlas);
                Map<Identifier, Sprite> map = extractSpriteMap(value);
                if (map != null) {
                    return map;
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, Sprite> extractSpriteMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return (Map<Identifier, Sprite>) map;
            }
            Object sample = map.values().iterator().next();
            if (sample instanceof Sprite) {
                return (Map<Identifier, Sprite>) map;
            }
        }
        if (value instanceof Iterable<?> iterable) {
            Map<Identifier, Sprite> map = new HashMap<>();
            for (Object item : iterable) {
                if (item instanceof Sprite sprite) {
                    map.put(sprite.getContents().getId(), sprite);
                }
            }
            return map.isEmpty() ? null : map;
        }
        if (value instanceof Sprite[] arr) {
            Map<Identifier, Sprite> map = new HashMap<>();
            for (Sprite sprite : arr) {
                if (sprite != null) {
                    map.put(sprite.getContents().getId(), sprite);
                }
            }
            return map.isEmpty() ? null : map;
        }
        return null;
    }
}
