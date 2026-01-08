package com.voxelbridge.export.texture;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.texture.Sprite;

import java.awt.image.BufferedImage;

/**
 * Manages BlockEntity textures by loading them and registering with the atlas system.
 */
public final class BlockEntityTextureManager {

    private BlockEntityTextureManager() {}

    private static TextureRepository repo(ExportContext ctx) {
        return ctx.getTextureRepository();
    }

    public static String registerGenerated(ExportContext ctx,
                                           com.voxelbridge.export.texture.EntityTextureManager.TextureHandle handle,
                                           BufferedImage image) {
        String spriteKey = handle.spriteKey().startsWith("blockentity:")
            ? handle.spriteKey()
            : "blockentity:" + handle.spriteKey();
        String loc = handle.textureLocation();

        TextureRepository repo = repo(ctx);
        repo.put(loc, spriteKey, image);

        ctx.getGeneratedEntityTextures().put(spriteKey, image);
        ctx.getMaterialPaths().putIfAbsent(spriteKey, handle.relativePath());
        ctx.getEntityTextures().putIfAbsent(spriteKey,
            new ExportState.EntityTexture(loc, image.getWidth(), image.getHeight()));
        TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

        VoxelBridgeLogger.info(LogModule.TEXTURE,
            "[BlockEntityTex] Registered generated texture: " + spriteKey + " -> " + loc);
        return spriteKey;
    }

    public static String registerTexture(ExportContext ctx, ResolvedTexture textureRes) {
        String textureKey = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(textureRes.texture().toString());
        String[] parts = splitKey(textureKey);
        if (parts == null) {
            throw new IllegalStateException("Invalid block entity texture key: " + textureKey);
        }
        String spriteKey = "blockentity:" + parts[0] + "/" + parts[1];

        VoxelBridgeLogger.info(LogModule.TEXTURE,
            "[BlockEntityTex] Registering texture: " + spriteKey + " from " + textureKey);

        String pngKey = ctx.getTextureAccess().ensurePngKey(textureKey);
        String rawKey = textureRes.texture() != null ? textureRes.texture().toString() : null;

        TextureRepository repo = repo(ctx);
        BufferedImage texture = repo.get(pngKey);
        String loadedKey = pngKey;

        if (texture == null && rawKey != null && !rawKey.equals(pngKey)) {
            texture = repo.get(rawKey);
            if (texture != null) {
                loadedKey = rawKey;
            }
        }

        if (texture == null) {
            texture = loadTextureFromResolved(ctx, textureRes, pngKey);
            if (texture != null) {
                loadedKey = pngKey;
                VoxelBridgeLogger.info(LogModule.TEXTURE,
                    "[BlockEntityTex] Loaded texture: " + loadedKey + " (" + texture.getWidth() + "x" + texture.getHeight() + ")");
            }
        }

        if (texture == null && rawKey != null && !rawKey.equals(pngKey)) {
            texture = loadTextureFromResolved(ctx, textureRes, rawKey);
            if (texture != null) {
                loadedKey = rawKey;
                VoxelBridgeLogger.info(LogModule.TEXTURE,
                    "[BlockEntityTex] Loaded texture: " + loadedKey + " (" + texture.getWidth() + "x" + texture.getHeight() + ")");
            }
        }

        if (texture == null) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Failed to load texture: " + pngKey);
        }

        if (texture != null) {
            if (com.voxelbridge.config.ExportRuntimeConfig.isAnimationEnabled()) {
                com.voxelbridge.core.texture.AnimatedFrameSet frames =
                    AnimatedTextureHelper.extractAndStore(ctx, spriteKey, texture, repo);
                if (frames != null && !frames.isEmpty()) {
                    texture = frames.frames().get(0);
                }
            }
            repo.put(loadedKey, spriteKey, texture);

            String relativePath = TexturePathResolver.ensureEntityLikePath(ctx, spriteKey);

            final BufferedImage texRef = texture;
            final String loadedKeyFinal = loadedKey;
            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                k -> new ExportState.EntityTexture(loadedKeyFinal, texRef.getWidth(), texRef.getHeight()));

            VoxelBridgeLogger.info(LogModule.TEXTURE,
                "[BlockEntityTex] Registered: " + spriteKey + " -> " + relativePath);
            TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

            var pbr = com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, textureRes.sprite());
            if (pbr.normalImage() != null && pbr.normalLocation() != null) {
                repo.put(pbr.normalLocation().toString(), normalKey(spriteKey), pbr.normalImage());
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_n"));
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportState.EntityTexture(pbr.normalLocation().toString(), pbr.normalImage().getWidth(), pbr.normalImage().getHeight()));
            }
            if (pbr.specularImage() != null && pbr.specularLocation() != null) {
                repo.put(pbr.specularLocation().toString(), specKey(spriteKey), pbr.specularImage());
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_s"));
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportState.EntityTexture(pbr.specularLocation().toString(), pbr.specularImage().getWidth(), pbr.specularImage().getHeight()));
            }

            if (textureRes.isAtlasTexture()) {
                tryCropPbrFromAtlas(ctx, textureRes, spriteKey);
            }

            trySiblingPbr(ctx, textureKey, spriteKey);
        } else {
            throw new IllegalStateException("Failed to load BlockEntity texture: " + textureKey);
        }

        return spriteKey;
    }

    public static BufferedImage getTexture(ExportContext ctx, String resourceKey) {
        return repo(ctx).get(resourceKey);
    }

    public static boolean hasTexture(ExportContext ctx, String resourceKey) {
        return repo(ctx).contains(resourceKey);
    }

    public static String getRegisteredLocation(ExportContext ctx, String spriteKey) {
        return repo(ctx).getRegisteredLocation(spriteKey);
    }

    public static String getTextureFilename(String spriteKey) {
        return "textures/blockentity/" + safe(spriteKey) + ".png";
    }

    private static BufferedImage loadTextureFromResource(ExportContext ctx, String resourceKey) {
        try {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Trying to load: " + resourceKey);
            return ctx.getTextureAccess().readTexture(resourceKey);
        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE,
                "[BlockEntityTex] Error loading texture " + resourceKey + ": " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage loadTextureFromResolved(ExportContext ctx, ResolvedTexture textureRes, String resourceKey) {
        if (textureRes.isAtlasTexture()) {
            Sprite sprite = textureRes.sprite();
            if (sprite != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE,
                    "[BlockEntityTex] Loading atlas sprite " + sprite.getContents().getId() +
                        " from atlas " + sprite.getAtlasId());
                return loadAtlasSprite(ctx, sprite);
            }
        }
        return loadTextureFromResource(ctx, resourceKey);
    }

    private static BufferedImage loadAtlasSprite(ExportContext ctx, Sprite sprite) {
        try {
            return ctx.getTextureAccess().readSprite(sprite);
        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE,
                "[BlockEntityTex] Error loading atlas sprite " + sprite.getContents().getId() + ": " + e.getMessage());
            return null;
        }
    }

    public static void clear(ExportContext ctx) {
        repo(ctx).clear();
    }

    private static String normalKey(String baseKey) {
        return baseKey + "_n";
    }

    private static String specKey(String baseKey) {
        return baseKey + "_s";
    }

    private static void tryCropPbrFromAtlas(ExportContext ctx, ResolvedTexture textureRes, String spriteKey) {
        String atlasKey = textureRes.atlasLocation() != null
            ? textureRes.atlasLocation().toString()
            : (textureRes.texture() != null ? textureRes.texture().toString() : null);
        if (atlasKey == null) return;
        String atlasNormalKey = ctx.getTextureAccess().appendSuffixKey(atlasKey, "_n");
        String atlasSpecKey = ctx.getTextureAccess().appendSuffixKey(atlasKey, "_s");

        float u0 = textureRes.u0();
        float u1 = textureRes.u1();
        float v0 = textureRes.v0();
        float v1 = textureRes.v1();

        if (ctx.getCachedSpriteImage(normalKey(spriteKey)) == null) {
            BufferedImage atlasImg = ctx.getTextureAccess().readTexture(atlasNormalKey);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                String genKey = ctx.getTextureAccess().generatedKey("voxelbridge", "generated/" + safe(normalKey(spriteKey)) + ".png");
                repo(ctx).put(genKey, normalKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_n"));
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportState.EntityTexture(genKey, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE,
                    "[BlockEntityTex][PBR] Cropped normal from atlas " + atlasNormalKey + " for " + spriteKey);
            }
        }
        if (ctx.getCachedSpriteImage(specKey(spriteKey)) == null) {
            BufferedImage atlasImg = ctx.getTextureAccess().readTexture(atlasSpecKey);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                String genKey = ctx.getTextureAccess().generatedKey("voxelbridge", "generated/" + safe(specKey(spriteKey)) + ".png");
                repo(ctx).put(genKey, specKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_s"));
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportState.EntityTexture(genKey, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE,
                    "[BlockEntityTex][PBR] Cropped specular from atlas " + atlasSpecKey + " for " + spriteKey);
            }
        }
    }

    private static void trySiblingPbr(ExportContext ctx, String baseTextureKey, String spriteKey) {
        boolean needNormal = ctx.getCachedSpriteImage(normalKey(spriteKey)) == null;
        boolean needSpec = ctx.getCachedSpriteImage(specKey(spriteKey)) == null;
        if (!needNormal && !needSpec) return;

        String pngBase = ctx.getTextureAccess().ensurePngKey(baseTextureKey);

        if (needNormal) {
            String sibNormalKey = ctx.getTextureAccess().appendSuffixKey(pngBase, "_n");
            BufferedImage img = ctx.getTextureAccess().readTexture(sibNormalKey);
            if (img != null) {
                repo(ctx).put(sibNormalKey, normalKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_n"));
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportState.EntityTexture(sibNormalKey, img.getWidth(), img.getHeight()));
            }
        }

        if (needSpec) {
            String sibSpecKey = ctx.getTextureAccess().appendSuffixKey(pngBase, "_s");
            BufferedImage img = ctx.getTextureAccess().readTexture(sibSpecKey);
            if (img != null) {
                repo(ctx).put(sibSpecKey, specKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx, spriteKey, "_s"));
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportState.EntityTexture(sibSpecKey, img.getWidth(), img.getHeight()));
            }
        }
    }

    private static BufferedImage crop(BufferedImage src, float u0, float u1, float v0, float v1) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int x0 = Math.max(0, Math.round(u0 * w));
        int x1 = Math.min(w, Math.round(u1 * w));
        int y0 = Math.max(0, Math.round(v0 * h));
        int y1 = Math.min(h, Math.round(v1 * h));
        int cw = Math.max(1, x1 - x0);
        int ch = Math.max(1, y1 - y0);
        if (x0 >= w || y0 >= h) return null;
        try {
            return src.getSubimage(x0, y0, cw, ch);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE, "[BlockEntityTex][WARN] Crop failed: " + e.getMessage());
            return null;
        }
    }

    private static String safe(String s) {
        return TexturePathResolver.safe(s);
    }

    private static String[] splitKey(String resourceKey) {
        if (resourceKey == null) {
            return null;
        }
        int split = resourceKey.indexOf(':');
        if (split <= 0 || split == resourceKey.length() - 1) {
            return null;
        }
        String namespace = resourceKey.substring(0, split);
        String path = resourceKey.substring(split + 1);
        return new String[] { namespace, path };
    }
}
