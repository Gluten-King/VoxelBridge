package com.voxelbridge.export.exporter.resolve;

import com.voxelbridge.platform.client.ClientAccess;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Finds the sprite inside an atlas that contains a given UV coordinate.
 */
public final class DefaultAtlasLocator implements AtlasLocator {
    private final ClientAccess clientAccess;

    public DefaultAtlasLocator(ClientAccess clientAccess) {
        this.clientAccess = clientAccess;
    }

    @Override
    public TextureAtlasSprite find(ResourceLocation atlasLocation, float u, float v) {
        if (atlasLocation == null) {
            return null;
        }
        var tex = clientAccess.getTextureManager().getTexture(atlasLocation);
        if (!(tex instanceof TextureAtlas atlas)) {
            return null;
        }
        for (TextureAtlasSprite sprite : com.voxelbridge.compat.AtlasCompat.getAllSprites(atlas)) {
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
