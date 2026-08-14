package com.voxelbridge.adapter;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class NeoForgeWorldAdapter implements WorldAdapter {
    
    @Override
    public LevelChunkSection getSection(LevelChunk chunk, int sectionIndex) {
        // In current MC version, section index is direct
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return null;
        }
        return sections[sectionIndex];
    }

    @Override
    public int getSectionIndexFromSectionY(LevelChunk chunk, int sectionY) {
        return chunk.getSectionIndexFromSectionY(sectionY);
    }

    @Override
    public BlockState getBlockState(LevelChunkSection section, int localX, int localY, int localZ) {
        return section.getBlockState(localX, localY, localZ);
    }

    @Override
    public int getMinSection(Level level) {
        return level.getMinSectionY();
    }

    @Override
    public int getMaxSection(Level level) {
        return level.getMaxSectionY();
    }

    @Override
    public int getMinBuildHeight(Level level) {
        return level.getMinY();
    }
}
