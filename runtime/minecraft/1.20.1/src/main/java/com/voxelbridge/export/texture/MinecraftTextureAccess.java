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
            ResourceLocation loc = new ResourceLocation(resource.toString());
            ResourceLocation normalized = normalizeDynamicMapLocation(loc);
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
            ResourceLocation loc = new ResourceLocation(resource.toString());
            var resOpt = rm.getResource(loc);
            if (resOpt.isEmpty()) {
                return null;
            }
            var res = resOpt.get();
            var meta = AnimationMetadataUtil.readSection(res.metadata());
            if (meta != null) {
                return AnimationMetadataUtil.toCoreMetadata(meta);
            }

            ResourceLocation metaLoc = new ResourceLocation(resource + ".mcmeta");
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
            return rm.getResource(new ResourceLocation(resource.toString())).isPresent();
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
            var resOpt = rm.getResource(new ResourceLocation(resource.toString()));
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

    private static ResourceLocation normalizeDynamicMapLocation(ResourceLocation loc) {
        if (loc == null) {
            return null;
        }
        String path = loc.getPath();
        if (path == null) {
            return null;
        }
        // normalize textures/maps/<id>.png -> map/<id>
        if (path.startsWith("textures/maps/")) {
            String file = path.substring("textures/maps/".length());
            int dot = file.indexOf('.');
            if (dot > 0) {
                file = file.substring(0, dot);
            }
            try {
                int id = Integer.parseInt(file);
                return new ResourceLocation(loc.getNamespace(), "map/" + id);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // normalize textures/map/<id>.png -> map/<id>
        if (path.startsWith("textures/map/")) {
            String file = path.substring("textures/map/".length());
            int dot = file.indexOf('.');
            if (dot > 0) {
                file = file.substring(0, dot);
            }
            try {
                int id = Integer.parseInt(file);
                return new ResourceLocation(loc.getNamespace(), "map/" + id);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // normalize textures/dynamic/map/<id>_<frame>.png -> maps/<id>
        if (path.startsWith("textures/dynamic/map/")) {
            String file = path.substring("textures/dynamic/map/".length());
            int dot = file.indexOf('.');
            if (dot > 0) {
                file = file.substring(0, dot);
            }
            int underscore = file.indexOf('_');
            String idStr = underscore > 0 ? file.substring(0, underscore) : file;
            try {
                int id = Integer.parseInt(idStr);
                return new ResourceLocation(loc.getNamespace(), "map/" + id);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // normalize maps/<id> -> map/<id>
        if (path.startsWith("maps/")) {
            String idStr = path.substring("maps/".length());
            try {
                int id = Integer.parseInt(idStr);
                return new ResourceLocation(loc.getNamespace(), "map/" + id);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
