package com.voxelbridge.export.texture;

import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.contract.SpriteRef;
import com.voxelbridge.pipeline.port.TextureSource;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.texture.TextureLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minecraft-backed texture access adapter.
 */
public final class MinecraftTextureAccess implements TextureSource {

    public static final MinecraftTextureAccess INSTANCE = new MinecraftTextureAccess();

    private MinecraftTextureAccess() {}

    @Override
    public BufferedImage readTexture(ResourceId resource, boolean preserveAnimationStrip) {
        if (resource == null) {
            return null;
        }
        try {
            ResourceLocation loc = ResourceLocation.parse(resource.toString());
            ResourceLocation normalized = MapTextureUtil.normalizeDynamicMapLocation(loc);
            if (normalized != null) {
                loc = normalized;
            }
            return TextureLoader.readTexture(loc, preserveAnimationStrip);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public BufferedImage readSprite(SpriteRef sprite) {
        return sprite == null ? null : readTexture(sprite.id(), false);
    }

    public BufferedImage readRuntimeSprite(TextureAtlasSprite sprite, boolean preserveAnimationStrip) {
        return sprite == null ? null : TextureLoader.fromSprite(sprite, preserveAnimationStrip);
    }

    public String resolveSpriteKey(TextureAtlasSprite sprite) {
        return sprite == null ? null : SpriteKeyResolver.resolve(sprite);
    }

    @Override
    public AnimationMetadata readAnimationMetadata(ResourceId resource) {
        if (resource == null) {
            return null;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            ResourceLocation loc = ResourceLocation.parse(resource.toString());
            var resOpt = rm.getResource(loc);
            if (resOpt.isEmpty()) {
                return null;
            }
            var res = resOpt.get();
            var meta = AnimationMetadataUtil.readSection(res.metadata());
            if (meta != null) {
                return AnimationMetadataUtil.toCoreMetadata(meta);
            }

            ResourceLocation metaLoc = ResourceLocation.parse(resource + ".mcmeta");
            var metaResOpt = rm.getResource(metaLoc);
            if (metaResOpt.isEmpty()) {
                return null;
            }
            try (InputStream in = metaResOpt.get().open()) {
                if (in == null) {
                    return null;
                }
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return AnimationMetadataUtil.parseMcmetaJson(json);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean hasResource(ResourceId resource) {
        if (resource == null) {
            return false;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            return rm.getResource(ResourceLocation.parse(resource.toString())).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public Set<ResourceId> listPngResources(String pathPrefix) {
        if (pathPrefix == null) {
            return Set.of();
        }
        String cleanPath = pathPrefix.endsWith("/") ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            return rm.listResources(cleanPath, loc -> loc.getPath().endsWith(".png"))
                .keySet()
                .stream()
                .map(location -> new ResourceId(location.getNamespace(), location.getPath()))
                .collect(Collectors.toSet());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    @Override
    public byte[] readResource(ResourceId resource) {
        if (resource == null) {
            return null;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            var resOpt = rm.getResource(ResourceLocation.parse(resource.toString()));
            if (resOpt.isEmpty()) {
                return null;
            }
            try (InputStream input = resOpt.get().open()) {
                return input.readAllBytes();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
