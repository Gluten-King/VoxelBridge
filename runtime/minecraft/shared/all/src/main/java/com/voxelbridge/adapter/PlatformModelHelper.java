package com.voxelbridge.adapter;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Platform-specific helper for model data access.
 * Handles access to BakedQuad internals which may be obfuscated or packed differently.
 */
public interface PlatformModelHelper {

    /**
     * Gets the sprite associated with a BakedQuad.
     */
    TextureAtlasSprite getQuadSprite(BakedQuad quad);

    /**
     * Gets the direction associated with a BakedQuad.
     */
    Direction getQuadDirection(BakedQuad quad);

    /**
     * Gets the raw vertex data of a BakedQuad.
     */
    int[] getQuadVertices(BakedQuad quad);

    /**
     * Gets the tint index of a BakedQuad.
     */
    int getQuadTintIndex(BakedQuad quad);
}
