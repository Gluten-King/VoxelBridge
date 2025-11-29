package com.voxelbridge.export.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * SpriteKeyResolver creates stable keys for atlas sprites across versions.
 */
@OnlyIn(Dist.CLIENT)
public final class SpriteKeyResolver {

    private SpriteKeyResolver() {}

    /**
     * Maps a {@link TextureAtlasSprite} to a deterministic key (e.g. minecraft:block/grass_block_top).
     */
    public static String resolve(TextureAtlasSprite sprite) {
        ResourceLocation name = sprite.contents().name();
        return name != null ? name.toString() : "minecraft:block/unknown";
    }
}
