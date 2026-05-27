package com.voxelbridge.adapter;

import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.adapter.QuadBatch;
import com.voxelbridge.adapter.QuadSource;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

public class NeoForgeRenderAdapter implements RenderAdapter {
    private static volatile boolean loggedModelDataFailure;
    private static volatile boolean loggedExtensionModelDataFailure;
    private static volatile boolean loggedNeoForgeQuadsFailure;
    private static volatile boolean loggedVanillaQuadsFailure;
    
    public NeoForgeRenderAdapter() {
    }

    @Override
    public Object getBlockModel(BlockState state) {
        var modelManager = ClientAccessHolder.get().getModelManager();
        if (modelManager == null) {
            return null;
        }
        return modelManager.getBlockModelShaper().getBlockModel(state);
    }

    @Override
    public List<BakedQuad> getQuads(Object model, BlockState state, BlockPos pos, BlockAndTintGetter level, long seed) {
        List<BakedQuad> quads = new ArrayList<>();
        if (!(model instanceof BakedModel bakedModel)) {
            return quads;
        }
        RandomSource rand = RandomSource.create(seed);

        ModelData modelData = ModelData.EMPTY;
        if (level instanceof Level l) {
            try {
                modelData = l.getModelData(pos);
            } catch (Throwable t) {
                logOnce(LogFailure.MODEL_DATA,
                    "[NeoForgeRenderAdapter] Failed to resolve ModelData; using ModelData.EMPTY", t);
            }
        }

        try {
            if (bakedModel instanceof IBakedModelExtension extension) {
                if (level instanceof Level l) {
                    modelData = extension.getModelData(l, pos, state, modelData);
                }
            }
        } catch (Throwable t) {
            logOnce(LogFailure.EXTENSION_MODEL_DATA,
                "[NeoForgeRenderAdapter] Failed to resolve extension ModelData; using previous ModelData", t);
        }

        try {
            for (Direction dir : Direction.values()) {
                List<BakedQuad> q = bakedModel.getQuads(state, dir, rand, modelData, null);
                if (q != null) quads.addAll(q);
            }
            List<BakedQuad> q2 = bakedModel.getQuads(state, null, rand, modelData, null);
            if (q2 != null) quads.addAll(q2);
        } catch (Throwable t) {
            logOnce(LogFailure.NEOFORGE_QUADS,
                "[NeoForgeRenderAdapter] NeoForge getQuads failed; falling back to vanilla getQuads", t);
            addVanillaQuads(bakedModel, state, rand, quads);
        }

        return quads;
    }

    @Override
    public QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos, BlockAndTintGetter level, long seed) {
        return new QuadBatch(QuadDataUtil.wrapBakedQuads(getQuads(model, state, pos, level, seed)), QuadSource.PLATFORM_DEFAULT);
    }

    @Override
    public String getSpriteName(TextureAtlasSprite sprite) {
        return SpriteKeyResolver.resolve(sprite);
    }

    private TextureAtlas getBlockAtlas() {
        var modelManager = ClientAccessHolder.get().getModelManager();
        return modelManager != null ? modelManager.getAtlas(TextureAtlas.LOCATION_BLOCKS) : null;
    }

    private static void addVanillaQuads(BakedModel bakedModel,
                                        BlockState state,
                                        RandomSource rand,
                                        List<BakedQuad> quads) {
        try {
            for (Direction dir : Direction.values()) {
                List<BakedQuad> q = bakedModel.getQuads(state, dir, rand);
                if (q != null) quads.addAll(q);
            }
            List<BakedQuad> q2 = bakedModel.getQuads(state, null, rand);
            if (q2 != null) quads.addAll(q2);
        } catch (Throwable t) {
            logOnce(LogFailure.VANILLA_QUADS,
                "[NeoForgeRenderAdapter] Vanilla getQuads fallback also failed", t);
        }
    }

    private enum LogFailure {
        MODEL_DATA,
        EXTENSION_MODEL_DATA,
        NEOFORGE_QUADS,
        VANILLA_QUADS
    }

    private static void logOnce(LogFailure failure, String message, Throwable t) {
        boolean shouldLog = switch (failure) {
            case MODEL_DATA -> !loggedModelDataFailure;
            case EXTENSION_MODEL_DATA -> !loggedExtensionModelDataFailure;
            case NEOFORGE_QUADS -> !loggedNeoForgeQuadsFailure;
            case VANILLA_QUADS -> !loggedVanillaQuadsFailure;
        };
        if (!shouldLog) {
            return;
        }
        switch (failure) {
            case MODEL_DATA -> loggedModelDataFailure = true;
            case EXTENSION_MODEL_DATA -> loggedExtensionModelDataFailure = true;
            case NEOFORGE_QUADS -> loggedNeoForgeQuadsFailure = true;
            case VANILLA_QUADS -> loggedVanillaQuadsFailure = true;
        }
        VoxelBridgeLogger.warn(LogModule.EXPORT,
            message + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
