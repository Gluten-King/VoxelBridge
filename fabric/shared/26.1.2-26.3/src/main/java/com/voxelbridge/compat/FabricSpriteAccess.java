package com.voxelbridge.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.mixin.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Fabric-specific sprite access using Mixin accessor.
 */
public final class FabricSpriteAccess {

    private FabricSpriteAccess() {
    }

    /**
     * Gets the original NativeImage from a sprite using Mixin accessor.
     */
    public static NativeImage getOriginalImage(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) {
            return null;
        }
        try {
            return ((SpriteContentsAccessor) sprite.contents()).voxelbridge$getOriginalImage();
        } catch (Throwable t) {
            return null;
        }
    }
}
