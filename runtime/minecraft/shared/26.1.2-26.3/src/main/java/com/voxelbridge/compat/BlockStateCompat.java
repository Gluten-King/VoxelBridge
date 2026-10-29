package com.voxelbridge.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.voxelbridge.adapter.Adapters;

/**
 * Version-agnostic helpers for BlockState API drift.
 * Delegates to PlatformRenderHelper.
 */
public final class BlockStateCompat {

    private BlockStateCompat() {}

    public static Vec3 getOffset(BlockState state, Level level, BlockPos pos) {
        return Adapters.getPlatformRenderHelper().getBlockOffset(state, level, pos);
    }

    public static boolean isSolidRender(BlockState state, Level level, BlockPos pos) {
        return Adapters.getPlatformRenderHelper().isSolidRender(state, level, pos);
    }
}
