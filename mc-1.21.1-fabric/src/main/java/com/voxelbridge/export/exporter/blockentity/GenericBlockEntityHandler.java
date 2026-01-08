package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Generic handler for all BlockEntities using Minecraft's BlockEntityRenderer system.
 */
public final class GenericBlockEntityHandler implements BlockEntityHandler {

    @Override
    public BlockEntityExportResult export(
        ExportContext ctx,
        World level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        BlockEntityRenderBatch renderBatch
    ) {
        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
            "[GenericBlockEntityHandler] Attempting to export BlockEntity: " + blockEntity.getClass().getSimpleName());
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
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[GenericBlockEntityHandler] Task created, renderBatch=" + (renderBatch != null ? "present" : "null"));
            if (renderBatch != null) {
                renderBatch.enqueue(task);
                rendered = true;
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Task enqueued to batch");
            } else {
                task.run();
                rendered = task.wasSuccessful();
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                    "[GenericBlockEntityHandler] Task executed immediately, success=" + rendered);
            }
        } else {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] createTask returned null");
        }

        if (rendered) {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[GenericBlockEntityHandler] Returning RENDERED_KEEP_BLOCK");
            return BlockEntityExportResult.RENDERED_KEEP_BLOCK;
        }

        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[GenericBlockEntityHandler] Returning NOT_HANDLED");
        return BlockEntityExportResult.NOT_HANDLED;
    }
}
