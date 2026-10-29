package com.voxelbridge.adapter;

import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.BlockStateModelExtension;

import java.util.ArrayList;
import java.util.List;

/** NeoForge 26.2 model adapter, including model-data-aware part collection. */
public final class NeoForgeRenderAdapter implements RenderAdapter {
    private static volatile boolean loggedCollectPartsFailure;

    @Override
    public Object getBlockModel(BlockState state) {
        var modelManager = ClientAccessHolder.get().getModelManager();
        return modelManager != null ? modelManager.getBlockStateModelSet().get(state) : null;
    }

    @Override
    public List<BakedQuad> getQuads(Object model, BlockState state, BlockPos pos,
                                    BlockAndLightGetter level, long seed) {
        List<BakedQuad> quads = new ArrayList<>();
        try {
            if (!(model instanceof BlockStateModel stateModel)) return quads;
            for (BlockStateModelPart part : collectParts(stateModel, state, pos, level, seed)) {
                quads.addAll(part.getQuads(null));
                for (Direction direction : Direction.values()) quads.addAll(part.getQuads(direction));
            }
        } catch (Throwable throwable) {
            logCollectPartsFailure(throwable);
        }
        return quads;
    }

    @Override
    public QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos,
                                  BlockAndLightGetter level, long seed) {
        List<QuadData> quads = new ArrayList<>();
        try {
            if (!(model instanceof BlockStateModel stateModel)) {
                return new QuadBatch(quads, QuadSource.PLATFORM_DEFAULT);
            }
            for (BlockStateModelPart part : collectParts(stateModel, state, pos, level, seed)) {
                quads.addAll(QuadDataUtil.wrapBakedQuads(part.getQuads(null), null));
                for (Direction direction : Direction.values()) {
                    quads.addAll(QuadDataUtil.wrapBakedQuads(part.getQuads(direction), direction));
                }
            }
        } catch (Throwable throwable) {
            logCollectPartsFailure(throwable);
        }
        return new QuadBatch(quads, QuadSource.PLATFORM_DEFAULT);
    }

    private static List<BlockStateModelPart> collectParts(BlockStateModel model, BlockState state,
            BlockPos pos, BlockAndLightGetter level, long seed) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        RandomSource random = RandomSource.create(seed);
        if (level instanceof BlockAndTintGetter tintedLevel
                && model instanceof BlockStateModelExtension extension) {
            extension.collectParts(tintedLevel, pos, state, random, parts);
        } else {
            model.collectParts(random, parts);
        }
        return parts;
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
