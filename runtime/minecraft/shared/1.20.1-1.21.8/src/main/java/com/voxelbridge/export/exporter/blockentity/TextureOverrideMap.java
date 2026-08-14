package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.texture.EntityTextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Allows redirecting or skipping textures during BlockEntity rendering.
 */
public interface TextureOverrideMap {

    /**
     * Resolves an override for the given sprite.
     *
     * @param spriteName original sprite
     * @return texture handle to use, or null to keep original
     */
    EntityTextureManager.TextureHandle resolve(ResourceLocation spriteName);

    /**
     * Indicates whether a quad should be skipped entirely.
     */
    boolean skipQuad(ResourceLocation spriteName, float[] localU, float[] localV);
}
