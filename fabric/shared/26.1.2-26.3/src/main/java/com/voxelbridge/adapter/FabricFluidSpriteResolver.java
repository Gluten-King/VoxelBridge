package com.voxelbridge.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.material.FluidState;

/**
 * Fabric fluid sprite resolver using the MC 26.1 FluidStateModelSet.
 */
public final class FabricFluidSpriteResolver implements FluidSpriteResolver {
    @Override
    public TextureAtlasSprite[] resolve(BlockAndLightGetter level, BlockPos pos, FluidState fluidState) {
        try {
            var fluidModel = Minecraft.getInstance()
                    .getModelManager()
                    .getFluidStateModelSet()
                    .get(fluidState);
            if (fluidModel == null) {
                return null;
            }
            TextureAtlasSprite still = fluidModel.stillMaterial().sprite();
            TextureAtlasSprite flow = fluidModel.flowingMaterial().sprite();
            return new TextureAtlasSprite[]{still, flow};
        } catch (Throwable t) {
            return null;
        }
    }
}
