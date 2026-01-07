package com.voxelbridge.adapter;

import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.block.BlockState;

/**
 * Abstraction layer for world and chunk access.
 */
public interface WorldAdapter {
    ChunkSection getSection(WorldChunk chunk, int sectionIndex);
    int getSectionIndexFromSectionY(WorldChunk chunk, int sectionY);
    BlockState getBlockState(ChunkSection section, int localX, int localY, int localZ);
    int getMinSection(World world);
    int getMaxSection(World world);
    int getMinBuildHeight(World world);
}
