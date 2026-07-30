package com.voxelbridge.export.texture;

import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.core.texture.TextureAccess;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.texture.TextureLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minecraft-backed texture access adapter.
 */
public final class MinecraftTextureAccess implements TextureAccess<TextureAtlasSprite> {

    public static final MinecraftTextureAccess INSTANCE = new MinecraftTextureAccess();

    private MinecraftTextureAccess() {}

    @Override
    public String spriteKeyToResourceKey(String spriteKey) {
        if (spriteKey == null) {
            return null;
        }
        try {
            return TextureLoader.spriteKeyToTexturePNG(spriteKey).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public BufferedImage readTexture(String resourceKey, boolean preserveAnimationStrip) {
        if (resourceKey == null) {
            return null;
        }
        try {
            Identifier loc = Identifier.parse(resourceKey);
            Identifier normalized = MapTextureUtil.normalizeDynamicMapLocation(loc);
            if (normalized != null) {
                loc = normalized;
            }
            return TextureLoader.readTexture(loc, preserveAnimationStrip);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public BufferedImage readSprite(TextureAtlasSprite sprite) {
        return sprite == null ? null : TextureLoader.fromSprite(sprite);
    }

    @Override
    public String resolveSpriteKey(TextureAtlasSprite sprite) {
        return sprite == null ? null : SpriteKeyResolver.resolve(sprite);
    }

    @Override
    public AnimationMetadata readAnimationMetadata(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            Identifier loc = Identifier.parse(resourceKey);
            var resOpt = rm.getResource(loc);
            if (resOpt.isEmpty()) {
                return null;
            }
            var res = resOpt.get();
            var meta = AnimationMetadataUtil.readSection(res.metadata());
            if (meta != null) {
                return AnimationMetadataUtil.toCoreMetadata(meta);
            }

            Identifier metaLoc = Identifier.parse(resourceKey + ".mcmeta");
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
    public boolean hasResource(String resourceKey) {
        if (resourceKey == null) {
            return false;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            return rm.getResource(Identifier.parse(resourceKey)).isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public Set<String> listPngResources(String pathPrefix) {
        if (pathPrefix == null) {
            return Set.of();
        }
        String cleanPath = pathPrefix.endsWith("/") ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            return rm.listResources(cleanPath, loc -> loc.getPath().endsWith(".png"))
                .keySet()
                .stream()
                .map(Identifier::toString)
                .collect(Collectors.toSet());
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    @Override
    public InputStream openResource(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            var resOpt = rm.getResource(Identifier.parse(resourceKey));
            if (resOpt.isEmpty()) {
                return null;
            }
            return resOpt.get().open();
        } catch (Exception ignored) {
            return null;
        }
    }
}
