package com.voxelbridge.adapter;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.material.FluidState;

public interface FluidSpriteResolver {
    TextureAtlasSprite[] resolve(BlockAndLightGetter level, BlockPos pos, FluidState fluidState);
}
