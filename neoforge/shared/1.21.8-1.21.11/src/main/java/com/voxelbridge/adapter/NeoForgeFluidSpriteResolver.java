package com.voxelbridge.adapter;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;

public final class NeoForgeFluidSpriteResolver implements FluidSpriteResolver {
    @Override
    public TextureAtlasSprite[] resolve(BlockAndTintGetter level, BlockPos pos, FluidState fluidState) {
        return FluidSpriteCache.getFluidSprites(level, pos, fluidState);
    }
}
