package com.voxelbridge.export.quad;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Neutral quad data interface used by common export pipeline.
 */
public interface QuadData {
    TextureAtlasSprite sprite();

    Direction direction();

    /**
     * Neighbor side that is allowed to cull this quad. This is distinct from
     * {@link #direction()}, which is the light/normal face on modern render APIs.
     */
    default Direction cullDirection() {
        return direction();
    }

    int[] vertices();

    int tintIndex();
}
