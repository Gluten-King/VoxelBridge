package com.voxelbridge.adapter;

import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.platform.render.frapi.FabricRenderApiHelper;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinderGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndLightGetter;
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
        return modelManager.getBlockStateModelSet().get(state);
    }

    @Override
    public List<BakedQuad> getQuads(Object model, BlockState state, BlockPos pos, BlockAndLightGetter level, long seed) {
        if (!(model instanceof BlockStateModel vanillaModel)) {
            return List.of();
        }
        RandomSource rand = RandomSource.create(seed);

        List<BlockStateModelPart> parts = new ArrayList<>();
        vanillaModel.collectParts(rand, parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            quads.addAll(part.getQuads(null));
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                quads.addAll(part.getQuads(direction));
            }
        }
        if (!quads.isEmpty()) {
            return quads;
        }

        if (!(model instanceof FabricBlockStateModel fabricModel)
                || !(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter)) {
            return quads;
        }
        SpriteFinder spriteFinder = getSpriteFinder();
        if (spriteFinder == null) {
            return quads;
        }

        rand.setSeed(seed);
        return FabricRenderApiHelper.extractQuads(fabricModel, level, state, pos, rand, spriteFinder);
    }

    @Override
    public QuadBatch getQuadBatch(Object model, BlockState state, BlockPos pos, BlockAndLightGetter level, long seed) {
        return new QuadBatch(QuadDataUtil.wrapBakedQuads(getQuads(model, state, pos, level, seed)), QuadSource.FRAPI);
    }

    @Override
    public String getSpriteName(TextureAtlasSprite sprite) {
        return SpriteKeyResolver.resolve(sprite);
    }

    private SpriteFinder getSpriteFinder() {
        var mc = ClientAccessHolder.get().getMinecraft();
        if (mc == null) {
            return null;
        }
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        if (atlas instanceof SpriteFinderGetter getter) {
            return getter.spriteFinder(QuadAtlas.BLOCK);
        }
        return null;
    }
}
