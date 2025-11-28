package com.voxelbridge.modhandler;

import com.voxelbridge.export.ExportContext;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * Mod-specific block handler that can supply custom quads for special blocks.
 */
public interface ModBlockHandler {

    /**
     * Optionally handle a block and provide custom quads.
     *
     * @return empty if not handled.
     */
    Optional<ModHandledQuads> handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        BakedModel bakedModel
    );
}

