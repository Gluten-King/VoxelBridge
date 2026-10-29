package com.voxelbridge.compat;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.voxelbridge.adapter.Adapters;

import java.util.Collection;

/**
 * Cross-platform TextureAtlas utilities.
 * Delegates to PlatformRenderHelper.
 */
public final class AtlasCompat {

    private AtlasCompat() {}

    /**
     * Gets all sprites from a texture atlas.
     */
    public static Collection<TextureAtlasSprite> getAllSprites(TextureAtlas atlas) {
        return Adapters.getTextureHelper().getAllSprites(atlas);
    }
}
