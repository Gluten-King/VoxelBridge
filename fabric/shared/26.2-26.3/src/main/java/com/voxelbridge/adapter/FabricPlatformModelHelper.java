package com.voxelbridge.adapter;

import com.voxelbridge.compat.FabricQuadAccess;
import com.voxelbridge.core.ir.RenderLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Fabric implementation of PlatformModelHelper.
 */
public class FabricPlatformModelHelper implements PlatformModelHelper {
    @Override
    public TextureAtlasSprite getQuadSprite(BakedQuad quad) {
        return FabricQuadAccess.getSprite(quad);
    }

    @Override
    public Direction getQuadDirection(BakedQuad quad) {
        return FabricQuadAccess.getDirection(quad);
    }

    @Override
    public int[] getQuadVertices(BakedQuad quad) {
        return FabricQuadAccess.getVertices(quad);
    }

    @Override
    public int getQuadTintIndex(BakedQuad quad) {
        return FabricQuadAccess.getTintIndex(quad);
    }

    @Override
    public RenderLayer getQuadRenderLayer(BakedQuad quad) {
        return FabricQuadAccess.getRenderLayer(quad);
    }
}
