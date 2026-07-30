package com.voxelbridge.adapter;

import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.platform.render.frapi.FabricRenderApiHelper;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class FabricRenderAdapter implements RenderAdapter {

    public FabricRenderAdapter() {
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
        if (!(model instanceof FabricBlockStateModel fabricModel)) {
            return quads;
        }
        RandomSource rand = RandomSource.create(seed);
        SpriteFinder spriteFinder = getSpriteFinder();
        if (spriteFinder == null) {
            return quads;
        }

        return FabricRenderApiHelper.extractQuads(fabricModel, level, state, pos, rand, spriteFinder);
    }

    @Override
    public QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos, BlockAndTintGetter level, long seed) {
        if (!(model instanceof FabricBlockStateModel fabricModel)) {
            return new QuadBatch(List.of(), QuadSource.FRAPI);
        }
        SpriteFinder spriteFinder = getSpriteFinder();
        if (spriteFinder == null) {
            return new QuadBatch(List.of(), QuadSource.FRAPI);
        }
        return new QuadBatch(
            FabricRenderApiHelper.extractQuadData(
                fabricModel, level, state, pos, RandomSource.create(seed), spriteFinder),
            QuadSource.FRAPI
        );
    }

    @Override
    public String getSpriteName(TextureAtlasSprite sprite) {
        return SpriteKeyResolver.resolve(sprite);
    }

    private SpriteFinder getSpriteFinder() {
        var modelManager = ClientAccessHolder.get().getModelManager();
        if (modelManager == null) {
            return null;
        }
        TextureAtlas atlas = net.minecraft.client.Minecraft.getInstance()
            .getAtlasManager()
            .getAtlasOrThrow(AtlasIds.BLOCKS);
        return SpriteFinder.get(atlas);
    }
}
