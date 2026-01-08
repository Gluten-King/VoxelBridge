package com.voxelbridge.fabric.mixin;

import com.voxelbridge.platform.texture.DynamicTextureReader;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureManager.class)
public final class TextureManagerMixin {
    @Inject(method = "registerTexture", at = @At("TAIL"))
    private void voxelbridge$cacheDynamicTexture(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        DynamicTextureReader.cacheDynamic(id, texture);
    }

    @Inject(method = "registerDynamicTexture", at = @At("RETURN"))
    private void voxelbridge$cacheRegisteredDynamicTexture(String id,
                                                           NativeImageBackedTexture texture,
                                                           CallbackInfoReturnable<Identifier> cir) {
        Identifier registered = cir.getReturnValue();
        if (registered != null) {
            DynamicTextureReader.cacheDynamic(registered, texture);
        }
    }
}
