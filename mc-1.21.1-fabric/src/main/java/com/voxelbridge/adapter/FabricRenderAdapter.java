package com.voxelbridge.adapter;

import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.modhandler.frapi.FabricApiHelper;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric render adapter (vanilla quad path).
 */
public class FabricRenderAdapter implements RenderAdapter {

    @Override
    public BakedModel getBlockModel(BlockState state) {
        var modelManager = ClientAccessHolder.get().getModelManager();
        if (modelManager == null) return null;
        return modelManager.getBlockModels().getModel(state);
    }

    @Override
    public List<BakedQuad> getQuads(BakedModel model, BlockState state, BlockPos pos, BlockRenderView level, long seed) {
        List<BakedQuad> frapiQuads = collectQuadsFrapi(model, state, pos, level, seed);
        if (!frapiQuads.isEmpty()) {
            return frapiQuads;
        }
        return collectQuadsVanilla(model, state, pos, level, seed);
    }

    private List<BakedQuad> collectQuadsVanilla(BakedModel model, BlockState state, BlockPos pos,
                                               BlockRenderView level, long seed) {
        List<BakedQuad> quads = new ArrayList<>();
        Random rand = Random.create(seed);
        try {
            for (Direction dir : Direction.values()) {
                List<BakedQuad> q = model.getQuads(state, dir, rand);
                if (q != null) quads.addAll(q);
            }
            List<BakedQuad> q2 = model.getQuads(state, null, rand);
            if (q2 != null) quads.addAll(q2);
        } catch (Throwable ignored) {}
        return quads;
    }

    private List<BakedQuad> collectQuadsFrapi(BakedModel model, BlockState state, BlockPos pos,
                                              BlockRenderView level, long seed) {
        SpriteFinder spriteFinder = getSpriteFinder();
        if (spriteFinder == null) return new ArrayList<>();
        if (model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter()) {
            Random rand = Random.create(seed);
            return FabricApiHelper.extractQuads(fabricModel, level, state, pos, rand, spriteFinder);
        }
        return new ArrayList<>();
    }

    @Override
    public String getSpriteName(Sprite sprite) {
        return SpriteKeyResolver.resolve(sprite);
    }

    private SpriteFinder getSpriteFinder() {
        var modelManager = ClientAccessHolder.get().getModelManager();
        if (modelManager == null) return null;
        var atlas = modelManager.getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        return SpriteFinder.get(atlas);
    }
}
