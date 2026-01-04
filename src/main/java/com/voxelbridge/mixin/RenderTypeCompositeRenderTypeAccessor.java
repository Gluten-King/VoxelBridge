package com.voxelbridge.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface RenderTypeCompositeRenderTypeAccessor {
    @Invoker("state")
    RenderType.CompositeState voxelbridge$state();
}
