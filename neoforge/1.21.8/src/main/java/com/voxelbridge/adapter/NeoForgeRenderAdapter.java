package com.voxelbridge.adapter;

import com.voxelbridge.adapter.QuadBatch;
import com.voxelbridge.adapter.QuadSource;
import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.BlockStateModelExtension;

import java.util.ArrayList;
import java.util.List;

public class NeoForgeRenderAdapter implements RenderAdapter {
    private static volatile boolean loggedCollectPartsFailure;
    
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
        RandomSource rand = RandomSource.create(seed);
        // Collect parts -> quads
        try {
            if (!(model instanceof BlockStateModel stateModel)) {
                return quads;
            }
            List<BlockModelPart> parts = new ArrayList<>();
            if (stateModel instanceof BlockStateModelExtension extension) {
                extension.collectParts(level, pos, state, rand, parts);
            } else {
                stateModel.collectParts(rand, parts);
            }
            for (BlockModelPart part : parts) {
                for (Direction dir : Direction.values()) {
                    List<BakedQuad> q = part.getQuads(dir);
                    if (q != null) quads.addAll(q);
                }
                // Null direction for non-directional quads (if supported)
                List<BakedQuad> q2 = part.getQuads(null);
                if (q2 != null) quads.addAll(q2);
            }
        } catch (Throwable t) {
            logCollectPartsFailure(t);
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

    private static void logCollectPartsFailure(Throwable t) {
        if (loggedCollectPartsFailure) {
            return;
        }
        loggedCollectPartsFailure = true;
        VoxelBridgeLogger.warn(LogModule.EXPORT,
            "[NeoForgeRenderAdapter] BlockStateModel quad extraction failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
