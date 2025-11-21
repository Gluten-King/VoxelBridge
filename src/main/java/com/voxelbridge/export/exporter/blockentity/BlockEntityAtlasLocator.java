package com.voxelbridge.export.exporter.blockentity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Finds the sprite inside an atlas that contains a given UV coordinate.
 */
@OnlyIn(Dist.CLIENT)
final class BlockEntityAtlasLocator {
    private final Minecraft mc;

    BlockEntityAtlasLocator(Minecraft mc) {
        this.mc = mc;
    }

    TextureAtlasSprite find(ResourceLocation atlasLocation, float u, float v) {
        if (atlasLocation == null) {
            return null;
        }
        var tex = mc.getTextureManager().getTexture(atlasLocation);
        if (!(tex instanceof TextureAtlas atlas)) {
            return null;
        }
        for (TextureAtlasSprite sprite : atlas.getTextures().values()) {
            if (contains(sprite, u, v)) {
                return sprite;
            }
        }
        return null;
    }

    private boolean contains(TextureAtlasSprite sprite, float u, float v) {
        return u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1();
    }
}
