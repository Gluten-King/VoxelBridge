package com.voxelbridge.adapter;

import com.voxelbridge.export.ExportContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public interface ModHandlerBridge {
    boolean shouldHandle(BlockEntity blockEntity);

    List<BakedQuad> handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        Object bakedModel
    );
}
