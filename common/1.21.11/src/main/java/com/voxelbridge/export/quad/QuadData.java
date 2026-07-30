package com.voxelbridge.export.quad;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Neutral quad data interface used by common export pipeline.
 */
public interface QuadData {
    TextureAtlasSprite sprite();

    Direction direction();

    int[] vertices();

    int tintIndex();
}
