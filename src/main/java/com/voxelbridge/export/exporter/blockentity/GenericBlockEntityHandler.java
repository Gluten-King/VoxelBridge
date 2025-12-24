package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Generic handler for all BlockEntities using Minecraft's BlockEntityRenderer system.
 * This is a catch-all handler that attempts to render any BlockEntity.
 */
@OnlyIn(Dist.CLIENT)
public final class GenericBlockEntityHandler implements BlockEntityHandler {

    @Override
    public BlockEntityExportResult export(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        BlockEntityRenderBatch renderBatch
    ) {
        com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Attempting to export BlockEntity: " + blockEntity.getClass().getSimpleName());
        BlockEntityRenderer.RenderTask task = BlockEntityRenderer.createTask(
            ctx,
            blockEntity,
            sceneSink,
            pos.getX() + offsetX,
            pos.getY() + offsetY,
            pos.getZ() + offsetZ,
            null
        );

        boolean rendered = false;
        if (task != null) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Task created, renderBatch=" + (renderBatch != null ? "present" : "null"));
            if (renderBatch != null) {
                renderBatch.enqueue(task);
                rendered = true; // scheduled for batch execution
                com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Task enqueued to batch");
            } else {
                task.run();
                rendered = task.wasSuccessful();
                com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Task executed immediately, success=" + rendered);
            }
        } else {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] createTask returned null");
        }

        if (rendered) {
            // Keep the block model (BlockEntity adds to it, doesn't replace)
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Returning RENDERED_KEEP_BLOCK");
            return BlockEntityExportResult.RENDERED_KEEP_BLOCK;
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Returning NOT_HANDLED");
        return BlockEntityExportResult.NOT_HANDLED;
    }
}


