package com.voxelbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$BooleanStateShard")
public interface RenderStateShardBooleanStateShardAccessor {
    @Accessor("enabled")
    boolean voxelbridge$isEnabled();
}
