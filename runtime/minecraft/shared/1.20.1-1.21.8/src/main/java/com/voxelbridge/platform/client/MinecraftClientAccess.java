package com.voxelbridge.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.PaintingTextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.function.Function;

/**
 * Default client access implementation backed by Minecraft singleton.
 */
public final class MinecraftClientAccess implements ClientAccess {
    @Override
    public Minecraft getMinecraft() {
        return Minecraft.getInstance();
    }

    @Override
    public ResourceManager getResourceManager() {
        return Minecraft.getInstance().getResourceManager();
    }

    @Override
    public TextureManager getTextureManager() {
        return Minecraft.getInstance().getTextureManager();
    }

    @Override
    public ModelManager getModelManager() {
        return Minecraft.getInstance().getModelManager();
    }

    @Override
    public Function<ResourceLocation, TextureAtlasSprite> getTextureAtlas(ResourceLocation atlas) {
        return Minecraft.getInstance().getTextureAtlas(atlas);
    }

    @Override
    public PaintingTextureManager getPaintingTextures() {
        return Minecraft.getInstance().getPaintingTextures();
    }
}
