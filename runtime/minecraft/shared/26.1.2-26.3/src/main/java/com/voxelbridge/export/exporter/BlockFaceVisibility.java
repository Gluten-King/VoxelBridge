package com.voxelbridge.export.exporter;

import com.voxelbridge.compat.BlockStateCompat;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Centralizes block-neighbor visibility checks used before geometry cleanup.
 */
final class BlockFaceVisibility {
    private final Level level;
    private final ClientChunkCache chunkCache;
    private final BlockPos regionMin;
    private final BlockPos regionMax;
    private final boolean fillCaves;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    BlockFaceVisibility(Level level, ClientChunkCache chunkCache, BlockPos regionMin, BlockPos regionMax,
                        boolean fillCaves) {
        this.level = level;
        this.chunkCache = chunkCache;
        this.regionMin = regionMin;
        this.regionMax = regionMax;
        this.fillCaves = fillCaves;
    }

    boolean areNeighborChunksLoadedForBlock(BlockPos pos) {
        if (chunkCache == null) return true;

        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        if (localX == 0 && isChunkMissing(cx - 1, cz)) return false;
        if (localX == 15 && isChunkMissing(cx + 1, cz)) return false;
        if (localZ == 0 && isChunkMissing(cx, cz - 1)) return false;
        return localZ != 15 || !isChunkMissing(cx, cz + 1);
    }

    byte[] buildSolidFaceOcclusionCache(BlockPos pos) {
        byte[] faceOcclusionCache = new byte[Direction.values().length];
        boolean fullyOccluded = true;
        for (Direction dir : Direction.values()) {
            int idx = dir.ordinal();
            mutablePos.setWithOffset(pos, dir);
            if (isOutsideRegion(mutablePos)) {
                faceOcclusionCache[idx] = 2;
                fullyOccluded = false;
                continue;
            }
            boolean occluded = isNeighborSolid(mutablePos);
            faceOcclusionCache[idx] = (byte) (occluded ? 1 : 2);
            if (!occluded) fullyOccluded = false;
        }
        return fullyOccluded ? null : faceOcclusionCache;
    }

    boolean isFaceOccludedCached(BlockPos pos, Direction face, byte[] cache) {
        if (cache == null) {
            return isFaceOccluded(pos, face);
        }
        int idx = face.ordinal();
        byte cached = cache[idx];
        if (cached == 1) return true;
        if (cached == 2) return false;
        boolean occluded = isFaceOccluded(pos, face);
        cache[idx] = (byte) (occluded ? 1 : 2);
        return occluded;
    }

    boolean isFaceOccludedBySameBlock(BlockState state, BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        if (isOutsideRegion(mutablePos)) return false;
        return isNeighborSameBlock(state, mutablePos);
    }

    private boolean isChunkMissing(int cx, int cz) {
        var chunk = chunkCache.getChunk(cx, cz, false);
        return chunk == null || chunk.isEmpty();
    }

    private boolean isFaceOccluded(BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        if (isOutsideRegion(mutablePos)) return false;
        return isNeighborSolid(mutablePos);
    }

    private boolean isOutsideRegion(BlockPos pos) {
        if (regionMin == null || regionMax == null) return false;
        return pos.getX() < regionMin.getX() || pos.getX() > regionMax.getX()
            || pos.getY() < regionMin.getY() || pos.getY() > regionMax.getY()
            || pos.getZ() < regionMin.getZ() || pos.getZ() > regionMax.getZ();
    }

    private boolean isNeighborSolid(BlockPos neighbor) {
        BlockState state;
        if (chunkCache != null) {
            int cx = neighbor.getX() >> 4;
            int cz = neighbor.getZ() >> 4;
            var chunk = chunkCache.getChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) return true;
            state = chunk.getBlockState(neighbor);
        } else {
            state = level.getBlockState(neighbor);
        }

        if (fillCaves
            && state.isAir()
            && level.getBrightness(LightLayer.SKY, neighbor) == 0) {
            return true;
        }

        return BlockStateCompat.isSolidRender(state, level, neighbor);
    }

    private boolean isNeighborSameBlock(BlockState state, BlockPos neighbor) {
        BlockState neighborState;
        if (chunkCache != null) {
            int cx = neighbor.getX() >> 4;
            int cz = neighbor.getZ() >> 4;
            var chunk = chunkCache.getChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) return false;
            neighborState = chunk.getBlockState(neighbor);
        } else {
            neighborState = level.getBlockState(neighbor);
        }
        return neighborState.getBlock() == state.getBlock();
    }
}
