package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.texture.EntityTextureManager;
import net.minecraft.resources.Identifier;

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
    EntityTextureManager.TextureHandle resolve(Identifier spriteName);

    /**
     * Indicates whether a quad should be skipped entirely.
     */
    boolean skipQuad(Identifier spriteName, float[] localU, float[] localV);
}
