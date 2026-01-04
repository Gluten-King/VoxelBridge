package com.voxelbridge.voxy.mesh;

import com.voxelbridge.voxy.common.world.WorldSection;
import com.voxelbridge.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Lightweight BlockGetter for a 32x32x32 section with neighbor snapshots.
 */
final class LodBlockGetter implements BlockGetter {
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

    private final Mapper mapper;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int minBuildHeight;
    private final int height;
    private final long[] data;
    private final long[] negX;
    private final long[] posX;
    private final long[] negY;
    private final long[] posY;
    private final long[] negZ;
    private final long[] posZ;

    LodBlockGetter(Mapper mapper,
                   int baseX,
                   int baseY,
                   int baseZ,
                   long[] data,
                   long[] negX,
                   long[] posX,
                   long[] negY,
                   long[] posY,
                   long[] negZ,
                   long[] posZ) {
        this.mapper = mapper;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.minBuildHeight = baseY;
        this.height = 32;
        this.data = data;
        this.negX = negX;
        this.posX = posX;
        this.negY = negY;
        this.posY = posY;
        this.negZ = negZ;
        this.posZ = posZ;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return resolveState(pos.getX(), pos.getY(), pos.getZ());
    }

    private BlockState resolveState(int x, int y, int z) {
        int lx = x - baseX;
        int ly = y - baseY;
        int lz = z - baseZ;

        long[] source = data;
        int sx = lx;
        int sy = ly;
        int sz = lz;
        int offsetCount = 0;

        if (lx < 0) {
            source = negX;
            sx = lx + 32;
            offsetCount++;
        } else if (lx >= 32) {
            source = posX;
            sx = lx - 32;
            offsetCount++;
        }

        if (ly < 0) {
            if (offsetCount > 0) {
                return AIR_STATE;
            }
            source = negY;
            sy = ly + 32;
            offsetCount++;
        } else if (ly >= 32) {
            if (offsetCount > 0) {
                return AIR_STATE;
            }
            source = posY;
            sy = ly - 32;
            offsetCount++;
        }

        if (lz < 0) {
            if (offsetCount > 0) {
                return AIR_STATE;
            }
            source = negZ;
            sz = lz + 32;
            offsetCount++;
        } else if (lz >= 32) {
            if (offsetCount > 0) {
                return AIR_STATE;
            }
            source = posZ;
            sz = lz - 32;
            offsetCount++;
        }

        if (source == null || sx < 0 || sx >= 32 || sy < 0 || sy >= 32 || sz < 0 || sz >= 32) {
            return AIR_STATE;
        }

        long id = source[WorldSection.getIndex(sx, sy, sz)];
        if (Mapper.isAir(id)) {
            return AIR_STATE;
        }
        return mapper.getBlockStateFromBlockId(Mapper.getBlockId(id));
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }
}
