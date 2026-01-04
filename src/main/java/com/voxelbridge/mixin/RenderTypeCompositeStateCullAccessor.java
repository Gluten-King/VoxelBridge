package com.voxelbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeState")
public interface RenderTypeCompositeStateCullAccessor {
    @Accessor("cullState")
    Object voxelbridge$getCullState();
}
