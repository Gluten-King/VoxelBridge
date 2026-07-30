package com.voxelbridge.export.texture;

import com.voxelbridge.ModConstants;
import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Locale;
public final class EntityTextureManager {

    private static final int DEFAULT_TEX_SIZE = 16;

    private EntityTextureManager() {}

    public static TextureHandle register(ExportContext ctx, String resourceKey) {
        String sanitized = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(resourceKey);
        String[] parts = splitKey(sanitized);
        if (parts == null) {
            String fallbackKey = "entity:unknown";
            String relativePath = TexturePathResolver.ensureEntityLikePath(
                ctx.getMaterialPaths(), fallbackKey, ExportOptions.fromRuntimeConfig());
            return new TextureHandle(fallbackKey, "mat_unknown", relativePath, sanitized);
        }
        String namespace = parts[0];
        String path = parts[1];
        String spritePath = (namespace + "/" + path).replace(':', '/');
        String key = "entity:" + spritePath;
        String materialName = ctx.getMaterialNameForSprite(key);
        String relativePath = TexturePathResolver.ensureEntityLikePath(
            ctx.getMaterialPaths(), key, ExportOptions.fromRuntimeConfig());

        ctx.getEntityTextures().computeIfAbsent(key, k -> loadTextureInfo(ctx, sanitized));

        // Ensure the texture repository has an entry for this sprite.
        var repo = ctx.getTextureRepository();
        BufferedImage cached = repo.get(sanitized);
        if (cached == null) {
            String pngKey = resolveTexturePath(sanitized);
            BufferedImage img = ctx.getTextureAccess().readTexture(pngKey, com.voxelbridge.config.ExportRuntimeConfig.isAnimationEnabled());
            if (img != null) {
                repo.put(sanitized, key, img);
            } else {
                // Preserve mapping so later sprite cache inserts can replace it.
                repo.register(key, sanitized);
            }
        } else {
            repo.register(key, sanitized);
        }

        // Entity textures are also packed in atlas mode. Register immediately so
        // geometry capture and atlas generation cannot observe different key sets.
        TextureAtlasManager.registerTint(ctx, key, 0xFFFFFF);

        return new TextureHandle(key, materialName, relativePath, sanitized);
    }

    private static ExportState.EntityTexture loadTextureInfo(ExportContext ctx, String resourceKey) {
        String sanitized = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(resourceKey);
        try {
            String pngKey = resolveTexturePath(sanitized);
            try (InputStream in = ctx.getTextureAccess().openResource(pngKey)) {
                if (in == null) {
                    return new ExportState.EntityTexture(sanitized, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
                }
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                if (img == null) {
                    return new ExportState.EntityTexture(sanitized, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
                }
                return new ExportState.EntityTexture(sanitized, img.getWidth(), img.getHeight());
            }
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE, String.format("[VoxelBridge][WARN] Failed to read entity texture %s: %s", sanitized, e.getMessage()));
            return new ExportState.EntityTexture(sanitized, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
        }
    }

    private static String resolveTexturePath(String resourceKey) {
        String[] parts = splitKey(resourceKey);
        if (parts == null) {
            return resourceKey;
        }
        String namespace = parts[0];
        String path = parts[1];
        // Dynamic skins (e.g., minecraft:skins/aw-*) live in TextureManager, not resources.
        if (path.startsWith("skins/") || path.startsWith("skin/")) {
            return resourceKey;
        }
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return namespace + ":" + path;
    }

    public static TextureHandle registerGenerated(ExportContext ctx, String key, String relativePath, BufferedImage image) {
        String generatedKey = generatedLocation(key);
        ctx.getGeneratedEntityTextures().putIfAbsent(key, image);
        ctx.getMaterialPaths().putIfAbsent(key, relativePath);
        ctx.getEntityTextures().putIfAbsent(key, new ExportState.EntityTexture(generatedKey, image.getWidth(), image.getHeight()));
        ctx.getTextureRepository().put(generatedKey, key, image);
        TextureAtlasManager.registerTint(ctx, key, 0xFFFFFF);
        String materialName = ctx.getMaterialNameForSprite(key);
        return new TextureHandle(key, materialName, relativePath, generatedKey);
    }

    private static String generatedLocation(String key) {
        return ModConstants.MOD_ID + ":generated/" + safe(key);
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String[] splitKey(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        int split = resourceKey.indexOf(':');
        if (split <= 0 || split == resourceKey.length() - 1) {
            return null;
        }
        String namespace = resourceKey.substring(0, split).toLowerCase(Locale.ROOT);
        String path = resourceKey.substring(split + 1);
        return new String[] { namespace, path };
    }

    public record TextureHandle(String spriteKey, String materialName, String relativePath, String textureLocation) {}
}
