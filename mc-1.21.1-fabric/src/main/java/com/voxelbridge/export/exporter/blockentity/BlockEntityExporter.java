package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * Main entry point for BlockEntity export.
 * Delegates to specialized handlers in priority order.
 */
public final class BlockEntityExporter {

    // List of handlers in priority order (specific handlers first, generic last)
    private static final List<BlockEntityHandler> HANDLERS = List.of(
        new BannerBlockEntityHandler(),
        new SignBlockEntityHandler(),
        new GenericBlockEntityHandler()
    );

    private BlockEntityExporter() {}

    /**
     * Attempts to export a BlockEntity using registered handlers.
     */
    public static BlockEntityExportResult export(
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
        if (blockEntity == null || !ctx.isBlockEntityExportEnabled()) {
            return BlockEntityExportResult.NOT_HANDLED;
        }

        for (BlockEntityHandler handler : HANDLERS) {
            BlockEntityExportResult result = handler.export(
                ctx, level, state, blockEntity, pos, sceneSink,
                offsetX, offsetY, offsetZ, renderBatch
            );

            if (result.rendered()) {
                return result;
            }
        }

        return BlockEntityExportResult.NOT_HANDLED;
    }
}
