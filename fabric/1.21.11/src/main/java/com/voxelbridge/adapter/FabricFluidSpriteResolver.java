package com.voxelbridge.adapter;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;

/**
 * Fabric fluid sprite resolver using Fabric API.
 */
public final class FabricFluidSpriteResolver implements FluidSpriteResolver {
    @Override
    public TextureAtlasSprite[] resolve(BlockAndTintGetter level, BlockPos pos, FluidState fluidState) {
        var handler = FluidRenderHandlerRegistry.INSTANCE.get(fluidState.getType());
        if (handler != null) {
            return handler.getFluidSprites(level, pos, fluidState);
        }
        return null;
    }
}
