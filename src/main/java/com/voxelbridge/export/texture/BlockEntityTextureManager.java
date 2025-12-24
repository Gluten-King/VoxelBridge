package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityTextureResolver;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.export.texture.AnimatedFrameSet;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.texture.TextureAtlasManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Manages BlockEntity textures by loading them and registering with the atlas system.
 * BlockEntity textures are complete texture files that need to be loaded differently
 * than sprite-based block textures.
 */
@OnlyIn(Dist.CLIENT)
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
        ResourceLocation loc = handle.textureLocation();

        TextureRepository repo = repo(ctx);
        repo.put(loc, spriteKey, image);

        ctx.getGeneratedEntityTextures().put(spriteKey, image);
        ctx.getMaterialPaths().putIfAbsent(spriteKey, handle.relativePath());
        ctx.getEntityTextures().putIfAbsent(spriteKey,
            new ExportContext.EntityTexture(loc, image.getWidth(), image.getHeight()));
        // Register into main atlas (merged atlas path)
        TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registered generated texture: " + spriteKey + " -> " + loc);
        return spriteKey;
    }

    /**
     * Registers a BlockEntity texture from a ResourceLocation.
     * Uses the same approach as the old EntityTextureManager for compatibility.
     * Returns the sprite key that should be used.
     */
    public static String registerTexture(ExportContext ctx, BlockEntityTextureResolver.ResolvedTexture textureRes) {
        ResourceLocation textureLoc = com.voxelbridge.util.ResourceLocationUtil.sanitize(textureRes.texture().toString());
        String spriteKey = "blockentity:" + textureLoc.getNamespace() + "/" + textureLoc.getPath();

        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registering texture: " + spriteKey + " from " + textureLoc);

        ResourceLocation pngLocation = ensurePngLocation(textureLoc);

        TextureRepository repo = repo(ctx);
        BufferedImage texture = repo.computeIfAbsent(pngLocation, loc -> {
            BufferedImage img = loadTextureFromResolved(textureRes, loc);
            if (img != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Loaded texture: " + loc + " (" + img.getWidth() + "x" + img.getHeight() + ")");
            } else {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Failed to load texture: " + loc);
            }
            return img;
        });

        // Register the texture with the export context (same as old EntityTextureManager)
        if (texture != null) {
            if (com.voxelbridge.config.ExportRuntimeConfig.isAnimationEnabled()) {
                AnimatedFrameSet frames = AnimatedTextureHelper.extractAndStore(spriteKey, texture, repo);
                if (frames != null && !frames.isEmpty()) {
                    texture = frames.frames().get(0);
                }
            }
            repo.put(pngLocation, spriteKey, texture);

            // Register material path (EntityTextureManager line 29-30)
            String relativePath = ctx.getMaterialPaths()
                .computeIfAbsent(spriteKey, k -> "entity_textures/" + safe(textureLoc.toString()) + ".png");

            // Register texture info with context (EntityTextureManager line 32)
            final BufferedImage texRef = texture;
            final ResourceLocation locRef = pngLocation;
            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                k -> new ExportContext.EntityTexture(locRef, texRef.getWidth(), texRef.getHeight()));

            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Registered: " + spriteKey + " -> " + relativePath);
            // Register into shared atlas flow (default tint)
            TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);

            // PBR companions (_n/_s) if present in RP
            var pbr = com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, textureRes.sprite());
            if (pbr.normalImage() != null && pbr.normalLocation() != null) {
                repo.put(pbr.normalLocation(), normalKey(spriteKey), pbr.normalImage());
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(textureLoc.toString()) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(pbr.normalLocation(), pbr.normalImage().getWidth(), pbr.normalImage().getHeight()));
            }
            if (pbr.specularImage() != null && pbr.specularLocation() != null) {
                repo.put(pbr.specularLocation(), specKey(spriteKey), pbr.specularImage());
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(textureLoc.toString()) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(pbr.specularLocation(), pbr.specularImage().getWidth(), pbr.specularImage().getHeight()));
            }

            // If atlas texture and _n/_s still missing, attempt atlas companion crop (e.g., chest atlas)
            if (textureRes.isAtlasTexture()) {
                tryCropPbrFromAtlas(ctx, textureRes, spriteKey);
            }

            // Final fallback: try sibling files next to the base texture (e.g., textures/entity/chest/normal_n.png)
            trySiblingPbr(ctx, textureLoc, spriteKey);
        } else {
            throw new IllegalStateException("Failed to load BlockEntity texture: " + textureLoc);
        }

        return spriteKey;
    }

    /**
     * Loads a BlockEntity texture from Minecraft's resource system.
     * This must be called to populate the texture cache before atlas generation.
     */
    public static BufferedImage getTexture(ExportContext ctx, ResourceLocation location) {
        return repo(ctx).get(location);
    }

    /**
     * Checks if a texture is loaded.
     */
    public static boolean hasTexture(ExportContext ctx, ResourceLocation location) {
        return repo(ctx).contains(location);
    }

    /**
     * Returns the registered PNG location for a spriteKey, if any.
     */
    public static ResourceLocation getRegisteredLocation(ExportContext ctx, String spriteKey) {
        return repo(ctx).getRegisteredLocation(spriteKey);
    }

    /**
     * Returns the relative filename (under the export root) for a sprite key.
     */
    public static String getTextureFilename(String spriteKey) {
        return "textures/blockentity/" + safe(spriteKey) + ".png";
    }

    /**
     * Exports all registered BlockEntity textures.
     * Same logic as old EntityTextureManager.dumpAll()
     */
    public static void exportTextures(ExportContext ctx, Path outDir) throws java.io.IOException {
        Map<String, ResourceLocation> registeredTextures = repo(ctx).getRegisteredTextures();
        if (registeredTextures.isEmpty()) {
            return;
        }

        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Exporting " + registeredTextures.size() + " textures");

        for (Map.Entry<String, ResourceLocation> entry : registeredTextures.entrySet()) {
            String spriteKey = entry.getKey();
            ResourceLocation pngLocation = entry.getValue();
            String relativePath = ctx.getMaterialPaths().get(spriteKey);

            if (relativePath == null) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE, "[BlockEntityTex][WARN] No relative path for: " + spriteKey);
                continue;
            }

            Path target = outDir.resolve(relativePath);

            // Skip if already exists
            if (java.nio.file.Files.exists(target)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Already exists: " + target);
                continue;
            }

            // Get texture from cache
            BufferedImage img = repo(ctx).get(pngLocation);
            if (img != null) {
                // Write cached texture
                java.nio.file.Files.createDirectories(target.getParent());
                javax.imageio.ImageIO.write(img, "png", target.toFile());
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Exported: " + target);
            } else {
                // Try to copy from resource manager (fallback)
                try {
                    var resource = net.minecraft.client.Minecraft.getInstance()
                        .getResourceManager().getResource(pngLocation);
                    if (resource.isPresent()) {
                        java.nio.file.Files.createDirectories(target.getParent());
                        try (java.io.InputStream in = resource.get().open()) {
                            java.nio.file.Files.copy(in, target);
                        }
                        VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Copied from resources: " + target);
                    } else {
                        VoxelBridgeLogger.warn(LogModule.TEXTURE, "[BlockEntityTex][WARN] Texture not found: " + pngLocation);
                    }
                } catch (Exception e) {
                    VoxelBridgeLogger.error(LogModule.TEXTURE, "[BlockEntityTex][ERROR] Failed to export " + spriteKey + ": " + e.getMessage());
                }
            }
        }
    }

    private static BufferedImage loadTextureFromResource(ResourceLocation location) {
        try {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Trying to load: " + location);

            // Load using TextureLoader to avoid gamma/ICC tweaks and to honor resource packs
            return com.voxelbridge.export.texture.TextureLoader.readTexture(location);

        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Error loading texture " + location + ": " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage loadTextureFromResolved(BlockEntityTextureResolver.ResolvedTexture textureRes, ResourceLocation location) {
        if (textureRes.isAtlasTexture()) {
            TextureAtlasSprite sprite = textureRes.sprite();
            if (sprite != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Loading atlas sprite " + sprite.contents().name() +
                    " from atlas " + sprite.atlasLocation());
                return loadAtlasSprite(sprite);
            }
        }
        return loadTextureFromResource(location);
    }

    private static BufferedImage loadAtlasSprite(TextureAtlasSprite sprite) {
        try {
            return com.voxelbridge.export.texture.TextureLoader.fromSprite(sprite);
        } catch (Exception e) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex] Error loading atlas sprite " + sprite.contents().name() + ": " + e.getMessage());
            return null;
        }
    }

    private static ResourceLocation ensurePngLocation(ResourceLocation location) {
        String path = location.getPath();
        // If it already ends with .png, use it directly
        if (path.endsWith(".png")) {
            return location;
        }
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        path = path + ".png";
        String namespace = location.getNamespace();
        return ResourceLocation.fromNamespaceAndPath(namespace != null ? namespace : "minecraft", path);
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
    private static void tryCropPbrFromAtlas(ExportContext ctx, BlockEntityTextureResolver.ResolvedTexture textureRes, String spriteKey) {
        ResourceLocation atlas = textureRes.atlasLocation() != null ? textureRes.atlasLocation() : textureRes.texture();
        if (atlas == null) return;

        ResourceLocation atlasNormal = withSuffix(atlas, "_n");
        ResourceLocation atlasSpec = withSuffix(atlas, "_s");

        float u0 = textureRes.u0();
        float u1 = textureRes.u1();
        float v0 = textureRes.v0();
        float v1 = textureRes.v1();

        // Normal
        if (ctx.getCachedSpriteImage(normalKey(spriteKey)) == null) {
            BufferedImage atlasImg = TextureLoader.readTexture(atlasNormal);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                ResourceLocation genLoc = generatedLocation(normalKey(spriteKey));
                repo(ctx).put(genLoc, normalKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(genLoc, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex][PBR] Cropped normal from atlas " + atlasNormal + " for " + spriteKey);
            }
        }
        // Specular
        if (ctx.getCachedSpriteImage(specKey(spriteKey)) == null) {
            BufferedImage atlasImg = TextureLoader.readTexture(atlasSpec);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                ResourceLocation genLoc = generatedLocation(specKey(spriteKey));
                repo(ctx).put(genLoc, specKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(genLoc, cropped.getWidth(), cropped.getHeight()));
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[BlockEntityTex][PBR] Cropped specular from atlas " + atlasSpec + " for " + spriteKey);
            }
        }
    }

    private static ResourceLocation withSuffix(ResourceLocation base, String suffix) {
        String path = base.getPath();
        String withoutPng = path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
        return ResourceLocation.fromNamespaceAndPath(base.getNamespace(), withoutPng + suffix + ".png");
    }

    private static ResourceLocation appendSuffix(ResourceLocation base, String suffix) {
        return withSuffix(base, suffix);
    }

    private static ResourceLocation generatedLocation(String spriteKey) {
        return ResourceLocation.fromNamespaceAndPath("voxelbridge", "generated/" + safe(spriteKey) + ".png");
    }

    /**
     * Fallback: attempt to load PBR maps from the same directory as the base texture (sibling files).
     * This covers resource packs that place entity PBR as textures/entity/.../foo_n.png instead of atlas.
     */
    private static void trySiblingPbr(ExportContext ctx, ResourceLocation baseTexture, String spriteKey) {
        // Only proceed if still missing
        boolean needNormal = ctx.getCachedSpriteImage(normalKey(spriteKey)) == null;
        boolean needSpec = ctx.getCachedSpriteImage(specKey(spriteKey)) == null;
        if (!needNormal && !needSpec) return;

        ResourceLocation pngBase = ensurePngLocation(baseTexture);

        if (needNormal) {
            ResourceLocation sibNormal = appendSuffix(pngBase, "_n");
            BufferedImage img = TextureLoader.readTexture(sibNormal);
            if (img != null) {
                repo(ctx).put(sibNormal, normalKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(sibNormal, img.getWidth(), img.getHeight()));
            }
        }

        if (needSpec) {
            ResourceLocation sibSpec = appendSuffix(pngBase, "_s");
            BufferedImage img = TextureLoader.readTexture(sibSpec);
            if (img != null) {
                repo(ctx).put(sibSpec, specKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(sibSpec, img.getWidth(), img.getHeight()));
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
        return s.replace(':', '_').replace('/', '_');
    }
}



