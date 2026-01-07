package com.voxelbridge.adapter;

import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.block.BlockState;

public class FabricWorldAdapter implements WorldAdapter {
    @Override
    public ChunkSection getSection(WorldChunk chunk, int sectionIndex) {
        ChunkSection[] sections = chunk.getSectionArray();
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        return sections[sectionIndex];
    }

    @Override
    public int getSectionIndexFromSectionY(WorldChunk chunk, int sectionY) {
        return chunk.getSectionIndex(sectionY << 4);
    }

    @Override
    public BlockState getBlockState(ChunkSection section, int localX, int localY, int localZ) {
        return section.getBlockState(localX, localY, localZ);
    }

    @Override
    public int getMinSection(World world) { return world.getBottomSectionCoord(); }
    @Override
    public int getMaxSection(World world) { return world.getTopSectionCoord(); }
    @Override
    public int getMinBuildHeight(World world) { return world.getBottomY(); }
}
