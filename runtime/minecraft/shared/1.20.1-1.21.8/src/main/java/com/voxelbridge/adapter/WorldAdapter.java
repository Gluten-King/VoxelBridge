package com.voxelbridge.adapter;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Abstraction layer for world and chunk access.
 * Isolates version-specific implementation details of chunk storage.
 */
public interface WorldAdapter {
    
    /**
     * Gets a chunk section. Returns null if the section is missing or empty.
     */
    LevelChunkSection getSection(LevelChunk chunk, int sectionIndex);

    /**
     * Converts a world Y coordinate (or section index iterator) to the internal section index.
     * Note: In 1.18+, this handles the offset from minBuildHeight.
     */
    int getSectionIndexFromSectionY(LevelChunk chunk, int sectionY);

    /**
     * Fast access to block state within a section.
     */
    BlockState getBlockState(LevelChunkSection section, int localX, int localY, int localZ);

    int getMinSection(Level level);

    int getMaxSection(Level level);
    
    int getMinBuildHeight(Level level);
}
