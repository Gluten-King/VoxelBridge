package com.voxelbridge.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Provides mapping-safe access to the render setup. Parsing RenderType's
 * debug string is not stable after Fabric remaps Minecraft classes.
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {

    @Accessor("state")
    RenderSetup voxelbridge$getState();
}
