package com.voxelbridge.export.quad;

import com.voxelbridge.core.ir.RenderLayer;
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

    /**
     * Terrain render layer used for glTF alphaMode (SOLID/CUTOUT/TRANSLUCENT).
     */
    default RenderLayer renderLayer() {
        return RenderLayer.UNKNOWN;
    }
}
