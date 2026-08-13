package com.voxelbridge.adapter;

import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.BlockStateModelExtension;

import java.util.ArrayList;
import java.util.List;

public class NeoForgeRenderAdapter implements RenderAdapter {
    private static volatile boolean loggedCollectPartsFailure;

    @Override
    public Object getBlockModel(BlockState state) {
        var modelManager = ClientAccessHolder.get().getModelManager();
        return modelManager != null ? modelManager.getBlockModelShaper().getBlockModel(state) : null;
    }

    @Override
    public List<BakedQuad> getQuads(Object model, BlockState state, BlockPos pos,
                                    BlockAndTintGetter level, long seed) {
        List<BakedQuad> quads = new ArrayList<>();
        try {
            if (!(model instanceof BlockStateModel stateModel)) return quads;
            RandomSource random = RandomSource.create(seed);
            List<BlockModelPart> parts = new ArrayList<>();
            if (stateModel instanceof BlockStateModelExtension extension) {
                extension.collectParts(level, pos, state, random, parts);
            } else {
                stateModel.collectParts(random, parts);
            }
            for (BlockModelPart part : parts) {
                for (Direction direction : Direction.values()) {
                    List<BakedQuad> directional = part.getQuads(direction);
                    if (directional != null) quads.addAll(directional);
                }
                List<BakedQuad> unculled = part.getQuads(null);
                if (unculled != null) quads.addAll(unculled);
            }
        } catch (Throwable throwable) {
            logCollectPartsFailure(throwable);
        }
        return quads;
    }

    @Override
    public QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos,
                                  BlockAndTintGetter level, long seed) {
        return new QuadBatch(QuadDataUtil.wrapBakedQuads(getQuads(model, state, pos, level, seed)),
                QuadSource.PLATFORM_DEFAULT);
    }

    @Override
    public String getSpriteName(TextureAtlasSprite sprite) {
        return SpriteKeyResolver.resolve(sprite);
    }

    private static void logCollectPartsFailure(Throwable throwable) {
        if (loggedCollectPartsFailure) return;
        loggedCollectPartsFailure = true;
        VoxelBridgeLogger.warn(LogModule.EXPORT,
                "[NeoForgeRenderAdapter] BlockStateModel quad extraction failed: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }
}
