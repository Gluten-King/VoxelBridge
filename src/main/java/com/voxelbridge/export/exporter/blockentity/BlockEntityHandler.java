package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Handler for exporting specific types of BlockEntities.
 * Each handler is responsible for rendering one or more BlockEntity types.
 */
@OnlyIn(Dist.CLIENT)
public interface BlockEntityHandler {

    /**
     * Attempts to export the given BlockEntity.
     *
     * @param ctx Export context with shared state
     * @param level World level
     * @param state Block state at the position
     * @param blockEntity The block entity to export
     * @param pos Position in world
     * @param sceneSink Output sink for geometry
     * @param offsetX X coordinate offset for centering
     * @param offsetY Y coordinate offset for centering
     * @param offsetZ Z coordinate offset for centering
     * @return Result indicating if export was handled and if block model should be replaced
     */
    BlockEntityExportResult export(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    );
}
