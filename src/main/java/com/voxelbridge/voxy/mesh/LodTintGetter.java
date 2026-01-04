package com.voxelbridge.voxy.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;

/**
 * Minimal BlockAndTintGetter used for tint resolution without world access.
 */
final class LodTintGetter implements BlockAndTintGetter {
    private final BlockState state;
    private final Biome biome;

    LodTintGetter(BlockState state, Biome biome) {
        this.state = state;
        this.biome = biome;
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return 0;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos) {
        return 0;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver colorResolver) {
        return colorResolver.getColor(biome, 0, 0);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return state.getFluidState();
    }

    @Override
    public int getHeight() {
        return 0;
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }
}
