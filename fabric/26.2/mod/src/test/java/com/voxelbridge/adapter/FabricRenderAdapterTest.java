package com.voxelbridge.adapter;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FabricRenderAdapterTest {
    @Test
    void extractsQuadsFromVanillaBlockStateModelParts() {
        BakedQuad quad = new BakedQuad(
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0),
            new Vector3f(1, 1, 0), new Vector3f(0, 1, 0),
            0L, 0L, 0L, 0L, Direction.NORTH,
            new BakedQuad.MaterialInfo(null, null, null, -1, true, 0)
        );
        BlockStateModelPart part = new BlockStateModelPart() {
            @Override
            public List<BakedQuad> getQuads(Direction direction) {
                return direction == null ? List.of(quad) : List.of();
            }

            @Override public boolean useAmbientOcclusion() { return true; }
            @Override public Material.Baked particleMaterial() { return null; }
            @Override public int materialFlags() { return 0; }
        };
        BlockStateModel model = new BlockStateModel() {
            @Override
            public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
                output.add(part);
            }

            @Override public Material.Baked particleMaterial() { return null; }
            @Override public int materialFlags() { return 0; }
        };

        List<BakedQuad> result = new FabricRenderAdapter().getQuads(
            model, null, null, null, 42L
        );

        assertEquals(List.of(quad), result);
    }
}