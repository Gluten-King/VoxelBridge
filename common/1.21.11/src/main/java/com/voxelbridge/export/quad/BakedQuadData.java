package com.voxelbridge.export.quad;

import com.voxelbridge.compat.QuadCompat;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * QuadData adapter that wraps a BakedQuad without copying.
 */
public final class BakedQuadData implements QuadData {
    private final BakedQuad quad;

    public BakedQuadData(BakedQuad quad) {
        this.quad = quad;
    }

    @Override
    public TextureAtlasSprite sprite() {
        return QuadCompat.getSprite(quad);
    }

    @Override
    public Direction direction() {
        return QuadCompat.getDirection(quad);
    }

    @Override
    public int[] vertices() {
        return QuadCompat.getVertices(quad);
    }

    @Override
    public int tintIndex() {
        return QuadCompat.getTintIndex(quad);
    }
}
