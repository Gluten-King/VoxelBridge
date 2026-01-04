package com.voxelbridge.mixin;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(RenderType.TextureStateShard.class)
public interface RenderTypeTextureStateShardAccessor {
    @Accessor("texture")
    Optional<ResourceLocation> voxelbridge$getTexture();
}
