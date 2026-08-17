package com.voxelbridge.compat;

import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.ir.RenderLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Version-agnostic access to baked quad data.
 * Delegates to PlatformModelHelper.
 */
public final class QuadCompat {

    private QuadCompat() {}

    public static TextureAtlasSprite getSprite(BakedQuad quad) {
        return Adapters.getModelHelper().getQuadSprite(quad);
    }

    public static Direction getDirection(BakedQuad quad) {
        return Adapters.getModelHelper().getQuadDirection(quad);
    }

    public static int[] getVertices(BakedQuad quad) {
        return Adapters.getModelHelper().getQuadVertices(quad);
    }

    public static int getTintIndex(BakedQuad quad) {
        return Adapters.getModelHelper().getQuadTintIndex(quad);
    }

    public static RenderLayer getRenderLayer(BakedQuad quad) {
        return Adapters.getModelHelper().getQuadRenderLayer(quad);
    }
}
