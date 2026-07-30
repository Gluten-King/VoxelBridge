package com.voxelbridge.adapter;

import com.voxelbridge.export.quad.QuadDataUtil;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Abstraction layer for rendering subsystem.
 * Isolates model retrieval, quad extraction, and texture resolution.
 */
public interface RenderAdapter {
    /**
     * Gets the baked model handle for a block state.
     */
    Object getBlockModel(BlockState state);

    /**
     * Extracts quads from a model.
     * This encapsulates the complexity of:
     * 1. Vanilla getQuads
     * 2. Fabric/NeoForge API extensions (CTM, Indigo, etc.)
     * 3. ModelData handling
     */
    List<BakedQuad> getQuads(Object model, BlockState state, BlockPos pos, BlockAndTintGetter level, long seed);

    /**
     * Extracts quads and tags their source for downstream routing.
     * Implementations may override to provide richer source info.
     */
    default QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos, BlockAndTintGetter level, long seed) {
        return new QuadBatch(QuadDataUtil.wrapBakedQuads(getQuads(model, state, pos, level, seed)), QuadSource.PLATFORM_DEFAULT);
    }

    /**
     * Resolves the unique resource location string for a texture sprite.
     * e.g., "minecraft:block/stone"
     */
    String getSpriteName(TextureAtlasSprite sprite);
}
