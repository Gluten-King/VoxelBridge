package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.platform.client.ClientAccess;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Finds the sprite inside an atlas that contains a given UV coordinate.
 */
final class BlockEntityAtlasLocator implements AtlasLocator {
    private final ClientAccess clientAccess;

    BlockEntityAtlasLocator(ClientAccess clientAccess) {
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
        try {
            Field field = SpriteAtlasTexture.class.getDeclaredField("sprites");
            field.setAccessible(true);
            Object value = field.get(atlas);
            if (value instanceof Map<?, ?> map) {
                return (Map<Identifier, Sprite>) map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
