package com.voxelbridge.modhandler;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.modhandler.yuushya.YuushyaShowBlockHandler;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Registry for mod-specific block handlers.
 */
public final class ModHandlerRegistry {

    private static final List<ModBlockHandler> HANDLERS = List.of(
        new YuushyaShowBlockHandler()
    );

    private ModHandlerRegistry() {}

    public static ModHandledQuads handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        BakedModel bakedModel
    ) {
        for (ModBlockHandler handler : HANDLERS) {
            Optional<ModHandledQuads> result = handler.handle(ctx, level, state, blockEntity, pos, bakedModel);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return null;
    }
}

