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
        try {
            // 1.21+: sprite.contents().name() -> ResourceLocation
            Object contents = sprite.contents();
            ResourceLocation name = (ResourceLocation) contents.getClass()
                    .getMethod("name")
                    .invoke(contents);
            return name.toString();
        } catch (Throwable ignore) {
            try {
                // Compatibility: legacy getName() fallback.
                ResourceLocation name = (ResourceLocation) sprite.getClass()
                        .getMethod("getName")
                        .invoke(sprite);
                return name.toString();
            } catch (Throwable t) {
                // Final fallback
                return "minecraft:block/unknown";
            }
        }
    }
}