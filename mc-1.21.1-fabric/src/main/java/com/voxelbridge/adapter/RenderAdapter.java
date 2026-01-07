package com.voxelbridge.adapter;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import net.minecraft.block.BlockState;
import net.minecraft.client.texture.Sprite;
import java.util.List;

/**
 * Abstraction layer for rendering subsystem.
 */
public interface RenderAdapter {
    BakedModel getBlockModel(BlockState state);
    List<BakedQuad> getQuads(BakedModel model, BlockState state, BlockPos pos, BlockRenderView level, long seed);
    String getSpriteName(Sprite sprite);
}
