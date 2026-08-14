package com.voxelbridge.adapter;

import com.voxelbridge.export.ExportContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * NeoForge 1.21.8 ModHandlerBridge implementation.
 * Since Yuushya and FRAPI mods don't have 1.21.8 versions, this is a no-op implementation.
 */
public final class NeoForgeModHandlerBridge implements ModHandlerBridge {
    @Override
    public boolean shouldHandle(BlockEntity blockEntity) {
        return false;
    }

    @Override
    public List<BakedQuad> handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        Object bakedModel
    ) {
        return null;
    }
}
