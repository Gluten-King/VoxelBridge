package com.voxelbridge.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Mapping-safe access to the texture bindings owned by a render setup. */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {

    @Accessor("textures")
    Map<String, ?> voxelbridge$getTextureBindings();
}
