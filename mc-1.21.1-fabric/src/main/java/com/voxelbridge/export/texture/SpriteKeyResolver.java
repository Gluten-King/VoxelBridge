package com.voxelbridge.export.texture;

import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;

/**
 * SpriteKeyResolver creates stable keys for atlas sprites across versions.
 */
public final class SpriteKeyResolver {

    private SpriteKeyResolver() {}

    /**
     * Maps a {@link Sprite} to a deterministic key (e.g. minecraft:block/grass_block_top).
     */
    public static String resolve(Sprite sprite) {
        Identifier name = sprite.getContents() != null ? sprite.getContents().getId() : null;
        return name != null ? name.toString() : "minecraft:block/unknown";
    }
}
