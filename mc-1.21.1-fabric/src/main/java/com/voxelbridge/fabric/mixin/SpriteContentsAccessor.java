package com.voxelbridge.fabric.mixin;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("image")
    NativeImage voxelbridge$getImage();

    @Accessor("mipmapLevelsImages")
    NativeImage[] voxelbridge$getMipmapLevelsImages();
}
