package com.voxelbridge.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.data.AtlasIds;
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
        final net.minecraft.client.renderer.texture.TextureAtlas[] resolved = { null };
        Minecraft.getInstance().getAtlasManager().forEach((definition, candidate) -> {
            if (atlas.equals(candidate.location())) {
                resolved[0] = candidate;
            }
        });
        if (resolved[0] == null && isChestSheet(atlas)) {
            resolved[0] = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CHESTS);
        }
        return resolved[0] != null ? resolved[0]::getSprite : ignored -> null;
    }

    @Override
    public TextureAtlas getTextureAtlasObject(Identifier atlas) {
        final TextureAtlas[] resolved = { null };
        Minecraft.getInstance().getAtlasManager().forEach((definition, candidate) -> {
            if (atlas.equals(candidate.location())) {
                resolved[0] = candidate;
            }
        });
        if (resolved[0] == null && isChestSheet(atlas)) {
            resolved[0] = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CHESTS);
        }
        return resolved[0];
    }

    @Override
    public Function<Identifier, TextureAtlasSprite> getPaintingAtlas() {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PAINTINGS)::getSprite;
    }

    private static boolean isChestSheet(Identifier atlas) {
        if (atlas == null) return false;
        String path = atlas.getPath();
        return "chest".equals(path) || "chests".equals(path)
            || path.endsWith("/chest.png") || path.endsWith("/chests.png");
    }
}
