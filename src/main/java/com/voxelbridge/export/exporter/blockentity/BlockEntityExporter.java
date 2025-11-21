package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Main entry point for BlockEntity export.
 * Delegates to specialized handlers in priority order.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityExporter {

    // List of handlers in priority order (specific handlers first, generic last)
    private static final List<BlockEntityHandler> HANDLERS = List.of(
        new BannerBlockEntityHandler(),
        new GenericBlockEntityHandler()
    );

    private BlockEntityExporter() {}

    /**
     * Attempts to export a BlockEntity using registered handlers.
     *
     * @param ctx Export context
     * @param level World level
     * @param state Block state
     * @param blockEntity The block entity to export
     * @param pos Position in world
     * @param sceneSink Output sink for geometry
     * @param offsetX X coordinate offset
     * @param offsetY Y coordinate offset
     * @param offsetZ Z coordinate offset
     * @return Result indicating if export was handled and if block model should be replaced
     */
    public static BlockEntityExportResult export(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        if (blockEntity == null || !ctx.isBlockEntityExportEnabled()) {
            return BlockEntityExportResult.NOT_HANDLED;
        }

        // Try each handler in order
        for (BlockEntityHandler handler : HANDLERS) {
            BlockEntityExportResult result = handler.export(
                ctx, level, state, blockEntity, pos, sceneSink,
                offsetX, offsetY, offsetZ
            );

            if (result.rendered()) {
                // Handler succeeded, return result
                return result;
            }
        }

        return BlockEntityExportResult.NOT_HANDLED;
    }
}
