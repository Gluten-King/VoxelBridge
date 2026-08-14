package com.voxelbridge.platform.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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

    Function<Identifier, TextureAtlasSprite> getTextureAtlas(Identifier atlas);

    /** Resolves the live atlas object for UV-based sprite lookup. */
    default TextureAtlas getTextureAtlasObject(Identifier atlas) {
        var texture = getTextureManager().getTexture(atlas);
        return texture instanceof TextureAtlas textureAtlas ? textureAtlas : null;
    }

    Function<Identifier, TextureAtlasSprite> getPaintingAtlas();
}
