package com.voxelbridge.compat;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import com.voxelbridge.adapter.Adapters;

/**
 * Version-agnostic access to baked quad data.
 * Delegates to PlatformRenderHelper.
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
}
