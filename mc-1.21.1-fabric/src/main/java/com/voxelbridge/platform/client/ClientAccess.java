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
 * Abstracts client-side Minecraft access points that are version sensitive.
 */
public interface ClientAccess {
    MinecraftClient getMinecraft();

    ResourceManager getResourceManager();

    TextureManager getTextureManager();

    BakedModelManager getModelManager();

    Function<Identifier, Sprite> getTextureAtlas(Identifier atlas);

    PaintingManager getPaintingTextures();
}
