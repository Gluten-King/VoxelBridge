package com.voxelbridge.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.CompositeState.class)
public interface RenderTypeCompositeStateAccessor {
    @Accessor("textureState")
    Object voxelbridge$getTextureState();
}
