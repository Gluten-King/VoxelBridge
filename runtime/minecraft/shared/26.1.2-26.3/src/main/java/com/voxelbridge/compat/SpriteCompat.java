package com.voxelbridge.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.voxelbridge.adapter.Adapters;

/**
 * Cross-platform sprite utilities for accessing internal data.
 * Delegates to PlatformRenderHelper.
 */
public final class SpriteCompat {

    private SpriteCompat() {}

    /**
     * Gets the original NativeImage from a sprite.
     */
    public static NativeImage getOriginalImage(TextureAtlasSprite sprite) {
        return Adapters.getTextureHelper().getOriginalImage(sprite);
    }
}
