package com.voxelbridge.platform.render.frapi;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric Rendering API helper for extracting quads from FabricBlockStateModel.
 */
public final class FabricRenderApiHelper {
    private FabricRenderApiHelper() {}

    public static List<BakedQuad> extractQuads(
        FabricBlockStateModel model,
        BlockAndLightGetter level,
        BlockState state,
        BlockPos pos,
        RandomSource rand,
        SpriteFinder spriteFinder
    ) {
        try {
            Renderer renderer = Renderer.get();
            if (renderer == null) return new ArrayList<>();

            List<BakedQuad> fabricQuads = new ArrayList<>();
            MutableMesh mesh = renderer.mutableMesh();
            QuadEmitter emitter = mesh.emitter();

            if (!(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter tintGetter)) {
                return fabricQuads;
            }
            model.emitQuads(emitter, tintGetter, pos, state, rand, face -> false);

            if (spriteFinder != null) {
                mesh.forEach(q -> {
                    TextureAtlasSprite sprite = spriteFinder.find(q);
                    if (sprite != null) {
                        fabricQuads.add(q.toBakedQuad(sprite));
                    }
                });
            }

            return fabricQuads;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

}
