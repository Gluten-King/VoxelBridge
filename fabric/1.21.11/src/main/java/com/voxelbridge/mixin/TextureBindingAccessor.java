package com.voxelbridge.mixin;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The nested TextureBinding record is package-private, so the target is named
 * as a string and callers interact with it through this accessor interface.
 */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface TextureBindingAccessor {

    @Accessor("location")
    Identifier voxelbridge$getLocation();
}
