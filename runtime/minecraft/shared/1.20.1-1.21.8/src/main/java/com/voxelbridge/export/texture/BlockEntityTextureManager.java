package com.voxelbridge.export.texture;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.awt.image.BufferedImage;

/**
 * Manages BlockEntity textures by loading them and registering with the atlas system.
 * BlockEntity textures are complete texture files that need to be loaded differently
 * than sprite-based block textures.
 */
public final class BlockEntityTextureManager {

    private static final int DEFAULT_NORMAL = PbrTextureHelper.DEFAULT_NORMAL_COLOR;
    private static final int DEFAULT_SPEC = PbrTextureHelper.DEFAULT_SPECULAR_COLOR;

    private BlockEntityTextureManager() {}

    private static TextureRepository repo(ExportContext ctx) {
        return ctx.getTextureRepository();
    }

    /**
     * Registers a generated texture (e.g., baked banner) directly.
     */
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
        // Register into main atlas (merged atlas path)
        TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registered generated texture: " + spriteKey + " -> " + loc);
        return spriteKey;
    }

    /**
     * Registers a BlockEntity texture from a sanitized resource key.
     * Uses the same approach as the old EntityTextureManager for compatibility.
     * Returns the sprite key that should be used.
     */
    public static String registerTexture(ExportContext ctx, ResolvedTexture textureRes) {
        String textureKey = com.voxelbridge.util.ResourceLocationUtil.sanitizeKey(textureRes.texture().toString());
        String[] parts = splitKey(textureKey);
        if (parts == null) {
            throw new IllegalStateException("Invalid block entity texture key: " + textureKey);
        }
        String spriteKey = "blockentity:" + parts[0] + "/" + parts[1];

        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registering texture: " + spriteKey + " from " + textureKey);

        String pngKey = ctx.ensurePngKey(textureKey);
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
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Loaded texture: " + loadedKey + " (" + texture.getWidth() + "x" + texture.getHeight() + ")");
            }
        }

        if (texture == null && rawKey != null && !rawKey.equals(pngKey)) {
            texture = loadTextureFromResolved(ctx, textureRes, rawKey);
            if (texture != null) {
                loadedKey = rawKey;
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Loaded texture: " + loadedKey + " (" + texture.getWidth() + "x" + texture.getHeight() + ")");
            }
        }

        if (texture == null) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Failed to load texture: " + pngKey);
        }

        // Register the texture with the export context (same as old EntityTextureManager)
        if (texture != null) {
            if (isRuntimeFontPage(textureRes)) {
                // Older clients can dump the font page with an opaque black
                // background. Normalize it here as well as in TextureLoader so
                // every version reaches the atlas with transparent background.
                DynamicTextureUtil.cleanGlyphPixels(texture);
            }
            if (ctx.textureOptions().animationEnabled()) {
                com.voxelbridge.core.texture.AnimatedFrameSet frames = AnimatedTextureHelper.extractAndStore(ctx, spriteKey, texture, repo);
                if (frames != null && !frames.isEmpty()) {
                    texture = frames.frames().get(0);
                }
            }
            repo.put(loadedKey, spriteKey, texture);

            // Register material path (EntityTextureManager line 29-30)
            String relativePath = TexturePathResolver.ensureEntityLikePath(
                ctx.getMaterialPaths(), spriteKey, ctx.textureOptions());

            // Register texture info with context (EntityTextureManager line 32)
            final BufferedImage texRef = texture;
            final String loadedKeyFinal = loadedKey;
            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                k -> new ExportState.EntityTexture(loadedKeyFinal, texRef.getWidth(), texRef.getHeight()));

            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registered: " + spriteKey + " -> " + relativePath);
            // Register into shared atlas flow (default tint)
            TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

            // Runtime font atlas pages are generated by Minecraft and do not
            // have resource-pack PBR siblings.  Probing default/0_n and
            // default/0_s would create misleading missing-texture warnings and
            // cache placeholder dynamic textures.
            if (!isRuntimeFontPage(textureRes)) {
                // PBR companions (_n/_s) if present in RP
                var pbr = com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, textureRes.sprite());
                if (pbr.normalImage() != null && pbr.normalLocation() != null) {
                    repo.put(pbr.normalLocation().toString(), normalKey(spriteKey), pbr.normalImage());
                    ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                        TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_n"));
                    ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                        new ExportState.EntityTexture(pbr.normalLocation().toString(), pbr.normalImage().getWidth(), pbr.normalImage().getHeight()));
                }
                if (pbr.specularImage() != null && pbr.specularLocation() != null) {
                    repo.put(pbr.specularLocation().toString(), specKey(spriteKey), pbr.specularImage());
                    ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                        TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_s"));
                    ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                        new ExportState.EntityTexture(pbr.specularLocation().toString(), pbr.specularImage().getWidth(), pbr.specularImage().getHeight()));
                }

                // If atlas texture and _n/_s still missing, attempt atlas companion crop (e.g., chest atlas)
                if (textureRes.isAtlasTexture()) {
                    tryCropPbrFromAtlas(ctx, textureRes, spriteKey);
                }

                // Final fallback: try sibling files next to the base texture (e.g., textures/entity/chest/normal_n.png)
                trySiblingPbr(ctx, textureKey, spriteKey);
            }
        } else {
            throw new IllegalStateException("Failed to load BlockEntity texture: " + textureKey);
        }

        return spriteKey;
    }

    private static boolean isRuntimeFontPage(ResolvedTexture textureRes) {
        if (textureRes == null || textureRes.texture() == null) {
            return false;
        }
        String path = textureRes.texture().getPath();
        return path != null && path.matches("(?:font/)?(?:default|uniform|alt)/\\d+");
    }

    /**
     * Loads a BlockEntity texture from Minecraft's resource system.
     * This must be called to populate the texture cache before atlas generation.
     */
    public static BufferedImage getTexture(ExportContext ctx, String resourceKey) {
        return repo(ctx).get(resourceKey);
    }

    /**
     * Checks if a texture is loaded.
     */
    public static boolean hasTexture(ExportContext ctx, String resourceKey) {
        return repo(ctx).contains(resourceKey);
    }

    /**
     * Returns the registered PNG location for a spriteKey, if any.
     */
    public static String getRegisteredLocation(ExportContext ctx, String spriteKey) {
        return repo(ctx).getRegisteredLocation(spriteKey);
    }

    /**
     * Returns the relative filename (under the export root) for a sprite key.
     */
    public static String getTextureFilename(String spriteKey) {
        return "textures/blockentity/" + safe(spriteKey) + ".png";
    }

    private static BufferedImage loadTextureFromResource(ExportContext ctx, String resourceKey) {
        try {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Trying to load: " + resourceKey);

            // Load using TextureLoader to avoid gamma/ICC tweaks and to honor resource packs
            return ctx.readTexture(resourceKey);

        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Error loading texture " + resourceKey + ": " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage loadTextureFromResolved(ExportContext ctx, ResolvedTexture textureRes, String resourceKey) {
        if (textureRes.isAtlasTexture()) {
            TextureAtlasSprite sprite = textureRes.sprite();
            if (sprite != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Loading atlas sprite " + sprite.contents().name() +
                    " from atlas " + sprite.atlasLocation());
                return loadAtlasSprite(ctx, sprite);
            }
        }
        return loadTextureFromResource(ctx, resourceKey);
    }

    private static BufferedImage loadAtlasSprite(ExportContext ctx, TextureAtlasSprite sprite) {
        try {
            return ctx.readSprite(sprite);
        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Error loading atlas sprite " + sprite.contents().name() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Clears the texture cache (call at start of each export).
     */
    public static void clear(ExportContext ctx) {
        repo(ctx).clear();
    }

    private static String normalKey(String baseKey) {
        return baseKey + "_n";
    }

    private static String specKey(String baseKey) {
        return baseKey + "_s";
    }

    /**
     * Some block entity textures come from atlases (e.g., chest/sign). Their PBR companions are often atlases like chest_n.png.
     * This attempts to load atlas-level _n/_s and crop the relevant UV rectangle into sprite-specific images.
     */
    private static void tryCropPbrFromAtlas(ExportContext ctx, ResolvedTexture textureRes, String spriteKey) {
        String atlasKey = textureRes.atlasLocation() != null
            ? textureRes.atlasLocation().toString()
            : (textureRes.texture() != null ? textureRes.texture().toString() : null);
        if (atlasKey == null) return;
        String atlasNormalKey = ctx.appendTextureSuffix(atlasKey, "_n");
        String atlasSpecKey = ctx.appendTextureSuffix(atlasKey, "_s");

        float u0 = textureRes.u0();
        float u1 = textureRes.u1();
        float v0 = textureRes.v0();
        float v1 = textureRes.v1();

        // Normal
        if (ctx.getCachedSpriteImage(normalKey(spriteKey)) == null) {
            BufferedImage atlasImg = ctx.readTexture(atlasNormalKey);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            cropped = com.voxelbridge.export.texture.PbrTextureHelper.sanitizeMissingNo(
                cropped, DEFAULT_NORMAL, normalKey(spriteKey));
            if (cropped != null) {
                String genKey = ctx.generatedTextureKey("voxelbridge", "generated/" + safe(normalKey(spriteKey)) + ".png");
                repo(ctx).put(genKey, normalKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_n"));
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportState.EntityTexture(genKey, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex][PBR] Cropped normal from atlas " + atlasNormalKey + " for " + spriteKey);
            }
        }
        // Specular
        if (ctx.getCachedSpriteImage(specKey(spriteKey)) == null) {
            BufferedImage atlasImg = ctx.readTexture(atlasSpecKey);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            cropped = com.voxelbridge.export.texture.PbrTextureHelper.sanitizeMissingNo(
                cropped, DEFAULT_SPEC, specKey(spriteKey));
            if (cropped != null) {
                String genKey = ctx.generatedTextureKey("voxelbridge", "generated/" + safe(specKey(spriteKey)) + ".png");
                repo(ctx).put(genKey, specKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_s"));
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportState.EntityTexture(genKey, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex][PBR] Cropped specular from atlas " + atlasSpecKey + " for " + spriteKey);
            }
        }
    }

    /**
     * Fallback: attempt to load PBR maps from the same directory as the base texture (sibling files).
     * This covers resource packs that place entity PBR as textures/entity/.../foo_n.png instead of atlas.
     */
    private static void trySiblingPbr(ExportContext ctx, String baseTextureKey, String spriteKey) {
        // Only proceed if still missing
        boolean needNormal = ctx.getCachedSpriteImage(normalKey(spriteKey)) == null;
        boolean needSpec = ctx.getCachedSpriteImage(specKey(spriteKey)) == null;
        if (!needNormal && !needSpec) return;

        String pngBase = ctx.ensurePngKey(baseTextureKey);

        if (needNormal) {
            String sibNormalKey = ctx.appendTextureSuffix(pngBase, "_n");
            BufferedImage img = ctx.readTexture(sibNormalKey);
            img = com.voxelbridge.export.texture.PbrTextureHelper.sanitizeMissingNo(
                img, DEFAULT_NORMAL, normalKey(spriteKey));
            if (img != null) {
                repo(ctx).put(sibNormalKey, normalKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_n"));
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportState.EntityTexture(sibNormalKey, img.getWidth(), img.getHeight()));
            }
        }

        if (needSpec) {
            String sibSpecKey = ctx.appendTextureSuffix(pngBase, "_s");
            BufferedImage img = ctx.readTexture(sibSpecKey);
            img = com.voxelbridge.export.texture.PbrTextureHelper.sanitizeMissingNo(
                img, DEFAULT_SPEC, specKey(spriteKey));
            if (img != null) {
                repo(ctx).put(sibSpecKey, specKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    TexturePathResolver.entityPbrPath(ctx.textureOptions(), spriteKey, "_s"));
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


