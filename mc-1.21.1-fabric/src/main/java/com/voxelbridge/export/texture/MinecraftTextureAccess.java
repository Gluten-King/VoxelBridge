package com.voxelbridge.export.texture;

import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.core.texture.TextureAccess;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.texture.TextureLoader;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minecraft-backed texture access adapter.
 */
public final class MinecraftTextureAccess implements TextureAccess<Sprite> {

    public static final MinecraftTextureAccess INSTANCE = new MinecraftTextureAccess();

    private MinecraftTextureAccess() {}

    @Override
    public String spriteKeyToResourceKey(String spriteKey) {
        if (spriteKey == null) {
            return null;
        }
        return TextureLoader.spriteKeyToTexturePNG(spriteKey).toString();
    }

    @Override
    public BufferedImage readTexture(String resourceKey, boolean preserveAnimationStrip) {
        if (resourceKey == null) {
            return null;
        }
        String normalized = ensurePngKey(resourceKey);
        Identifier loc = Identifier.tryParse(normalized);
        if (loc == null) {
            return null;
        }
        return TextureLoader.readTexture(loc, preserveAnimationStrip);
    }

    @Override
    public BufferedImage readSprite(Sprite sprite) {
        return sprite == null ? null : TextureLoader.fromSprite(sprite);
    }

    @Override
    public String resolveSpriteKey(Sprite sprite) {
        return sprite == null ? null : com.voxelbridge.adapter.Adapters.getRender().getSpriteName(sprite);
    }

    @Override
    public AnimationMetadata readAnimationMetadata(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        try {
            var rm = ClientAccessHolder.get().getResourceManager();
            String normalized = ensurePngKey(resourceKey);
            Identifier loc = Identifier.tryParse(normalized);
            if (loc == null) {
                return null;
            }
            var resOpt = rm.getResource(loc);
            if (resOpt.isEmpty()) {
                return null;
            }
            var res = resOpt.get();
            Optional<AnimationResourceMetadata> metaOpt =
                res.getMetadata().decode(AnimationResourceMetadata.READER);
            AnimationResourceMetadata meta = metaOpt.orElse(null);
            if (meta == null) {
                return null;
            }
            List<AnimationMetadata.FrameTiming> timings = new ArrayList<>();
            meta.forEachFrame((idx, time) -> timings.add(new AnimationMetadata.FrameTiming(idx, time)));
            return new AnimationMetadata(meta.getDefaultFrameTime(), timings, meta.shouldInterpolate(), 0, 0);
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
            String normalized = ensurePngKey(resourceKey);
            Identifier loc = Identifier.tryParse(normalized);
            return loc != null && rm.getResource(loc).isPresent();
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
            return rm.findResources(cleanPath, loc -> loc.getPath().endsWith(".png"))
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
            String normalized = ensurePngKey(resourceKey);
            Identifier loc = Identifier.tryParse(normalized);
            if (loc == null) {
                return null;
            }
            var resOpt = rm.getResource(loc);
            if (resOpt.isEmpty()) {
                return null;
            }
            return resOpt.get().getInputStream();
        } catch (Exception ignored) {
            return null;
        }
    }
}
