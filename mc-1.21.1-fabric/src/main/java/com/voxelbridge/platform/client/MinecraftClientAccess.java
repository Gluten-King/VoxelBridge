package com.voxelbridge.platform.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.texture.PaintingManager;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.client.texture.Sprite;

import java.util.function.Function;

/**
 * Default client access implementation backed by Minecraft singleton.
 */
public final class MinecraftClientAccess implements ClientAccess {
    @Override
    public MinecraftClient getMinecraft() {
        return MinecraftClient.getInstance();
    }

    @Override
    public ResourceManager getResourceManager() {
        return MinecraftClient.getInstance().getResourceManager();
    }

    @Override
    public TextureManager getTextureManager() {
        return MinecraftClient.getInstance().getTextureManager();
    }

    @Override
    public BakedModelManager getModelManager() {
        return MinecraftClient.getInstance().getBakedModelManager();
    }

    @Override
    public Function<Identifier, Sprite> getTextureAtlas(Identifier atlas) {
        return MinecraftClient.getInstance().getSpriteAtlas(atlas);
    }

    @Override
    public PaintingManager getPaintingTextures() {
        return MinecraftClient.getInstance().getPaintingManager();
    }
}
