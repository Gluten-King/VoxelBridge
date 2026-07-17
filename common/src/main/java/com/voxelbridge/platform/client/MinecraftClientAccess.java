package com.voxelbridge.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
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
    public Function<Identifier, TextureAtlasSprite> getTextureAtlas(Identifier atlas) {
        Identifier atlasId = atlasDefinitionId(atlas);
        return id -> Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(atlasId).getSprite(id);
    }

    private static Identifier atlasDefinitionId(Identifier textureId) {
        String path = textureId.getPath();
        if (path.startsWith("textures/atlas/") && path.endsWith(".png")) {
            path = path.substring("textures/atlas/".length(), path.length() - ".png".length());
        }
        return Identifier.fromNamespaceAndPath(textureId.getNamespace(), path);
    }
}
