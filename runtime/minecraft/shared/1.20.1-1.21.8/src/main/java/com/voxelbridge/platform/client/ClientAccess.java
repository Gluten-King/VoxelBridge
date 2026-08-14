package com.voxelbridge.platform.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.PaintingTextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.function.Function;

/**
 * Abstracts client-side Minecraft access points that are version sensitive.
 */
public interface ClientAccess {
    Minecraft getMinecraft();

    ResourceManager getResourceManager();

    TextureManager getTextureManager();

    ModelManager getModelManager();

    Function<ResourceLocation, TextureAtlasSprite> getTextureAtlas(ResourceLocation atlas);

    PaintingTextureManager getPaintingTextures();
}
