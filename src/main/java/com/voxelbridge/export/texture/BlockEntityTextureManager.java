package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityTextureResolver;
import com.voxelbridge.util.ExportLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

/**
 * Manages BlockEntity textures by loading them and registering with the atlas system.
 * BlockEntity textures are complete texture files that need to be loaded differently
 * than sprite-based block textures.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityTextureManager {

    // Cache loaded textures to avoid reloading
    private static final Map<ResourceLocation, BufferedImage> textureCache = new ConcurrentHashMap<>();
    // Track spriteKey -> resolved texture location for exporting
    private static final Map<String, ResourceLocation> registeredTextures = new ConcurrentHashMap<>();
    private static final int DEFAULT_NORMAL = 0xFF8080FF; // 128/128/255/255
    private static final int DEFAULT_SPEC = 0x00000000;   // 0/0/0/0

    private BlockEntityTextureManager() {}

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

        textureCache.put(loc, image);
        registeredTextures.put(spriteKey, loc);

        ctx.getGeneratedEntityTextures().put(spriteKey, image);
        ctx.getMaterialPaths().putIfAbsent(spriteKey, handle.relativePath());
        ctx.getEntityTextures().putIfAbsent(spriteKey,
            new ExportContext.EntityTexture(loc, image.getWidth(), image.getHeight()));

        ExportLogger.log("[BlockEntityTex] Registered generated texture: " + spriteKey + " -> " + loc);
        return spriteKey;
    }

    /**
     * Registers a BlockEntity texture from a ResourceLocation.
     * Uses the same approach as the old EntityTextureManager for compatibility.
     * Returns the sprite key that should be used.
     */
    public static String registerTexture(ExportContext ctx, BlockEntityTextureResolver.ResolvedTexture textureRes) {
        ResourceLocation textureLoc = textureRes.texture();
        String spriteKey = "blockentity:" + textureLoc.getNamespace() + "/" + textureLoc.getPath();

        ExportLogger.log("[BlockEntityTex] Registering texture: " + spriteKey + " from " + textureLoc);

        ResourceLocation pngLocation = ensurePngLocation(textureLoc);

        BufferedImage texture = textureCache.computeIfAbsent(pngLocation, loc -> {
            BufferedImage img = loadTextureFromResolved(textureRes, loc);
            if (img != null) {
                ExportLogger.log("[BlockEntityTex] Loaded texture: " + loc + " (" + img.getWidth() + "x" + img.getHeight() + ")");
            } else {
                ExportLogger.log("[BlockEntityTex] Failed to load texture: " + loc);
            }
            return img;
        });

        // Register the texture with the export context (same as old EntityTextureManager)
        if (texture != null) {
            registeredTextures.put(spriteKey, pngLocation);

            // Register material path (EntityTextureManager line 29-30)
            String relativePath = ctx.getMaterialPaths()
                .computeIfAbsent(spriteKey, k -> "entity_textures/" + safe(textureLoc.toString()) + ".png");

            // Register texture info with context (EntityTextureManager line 32)
            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                k -> new ExportContext.EntityTexture(pngLocation, texture.getWidth(), texture.getHeight()));

            ExportLogger.log("[BlockEntityTex] Registered: " + spriteKey + " -> " + relativePath);

            // PBR companions (_n/_s) if present in RP
            var pbr = com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, textureRes.sprite());
            if (pbr.normalImage() != null && pbr.normalLocation() != null) {
                registeredTextures.put(normalKey(spriteKey), pbr.normalLocation());
                textureCache.put(pbr.normalLocation(), pbr.normalImage());
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(textureLoc.toString()) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(pbr.normalLocation(), pbr.normalImage().getWidth(), pbr.normalImage().getHeight()));
                logBerProbe(ctx, "[BER-PBR] direct normal hit: " + pbr.normalLocation());
            } else {
                logBerProbe(ctx, "[BER-PBR] direct normal miss for " + spriteKey);
            }
            if (pbr.specularImage() != null && pbr.specularLocation() != null) {
                registeredTextures.put(specKey(spriteKey), pbr.specularLocation());
                textureCache.put(pbr.specularLocation(), pbr.specularImage());
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(textureLoc.toString()) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(pbr.specularLocation(), pbr.specularImage().getWidth(), pbr.specularImage().getHeight()));
                logBerProbe(ctx, "[BER-PBR] direct specular hit: " + pbr.specularLocation());
            } else {
                logBerProbe(ctx, "[BER-PBR] direct specular miss for " + spriteKey);
            }

            // If atlas texture and _n/_s still missing, attempt atlas companion crop (e.g., chest atlas)
            if (textureRes.isAtlasTexture()) {
                tryCropPbrFromAtlas(ctx, textureRes, spriteKey);
            }

            // Final fallback: try sibling files next to the base texture (e.g., textures/entity/chest/normal_n.png)
            trySiblingPbr(ctx, textureLoc, spriteKey);
        }

        return spriteKey;
    }

    /**
     * Loads a BlockEntity texture from Minecraft's resource system.
     * This must be called to populate the texture cache before atlas generation.
     */
    public static BufferedImage getTexture(ResourceLocation location) {
        return textureCache.get(location);
    }

    /**
     * Checks if a texture is loaded.
     */
    public static boolean hasTexture(ResourceLocation location) {
        return textureCache.containsKey(location);
    }

    /**
     * Returns the registered PNG location for a spriteKey, if any.
     */
    public static ResourceLocation getRegisteredLocation(String spriteKey) {
        return registeredTextures.get(spriteKey);
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
        if (registeredTextures.isEmpty()) {
            return;
        }

        ExportLogger.log("[BlockEntityTex] Exporting " + registeredTextures.size() + " textures");

        for (Map.Entry<String, ResourceLocation> entry : registeredTextures.entrySet()) {
            String spriteKey = entry.getKey();
            ResourceLocation pngLocation = entry.getValue();
            String relativePath = ctx.getMaterialPaths().get(spriteKey);

            if (relativePath == null) {
                ExportLogger.log("[BlockEntityTex][WARN] No relative path for: " + spriteKey);
                continue;
            }

            Path target = outDir.resolve(relativePath);

            // Skip if already exists
            if (java.nio.file.Files.exists(target)) {
                ExportLogger.log("[BlockEntityTex] Already exists: " + target);
                continue;
            }

            // Get texture from cache
            BufferedImage img = textureCache.get(pngLocation);
            if (img != null) {
                // Write cached texture
                java.nio.file.Files.createDirectories(target.getParent());
                javax.imageio.ImageIO.write(img, "png", target.toFile());
                ExportLogger.log("[BlockEntityTex] Exported: " + target);
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
                        ExportLogger.log("[BlockEntityTex] Copied from resources: " + target);
                    } else {
                        ExportLogger.log("[BlockEntityTex][WARN] Texture not found: " + pngLocation);
                    }
                } catch (Exception e) {
                    ExportLogger.log("[BlockEntityTex][ERROR] Failed to export " + spriteKey + ": " + e.getMessage());
                }
            }
        }
    }

    private static BufferedImage loadTextureFromResource(ResourceLocation location) {
        try {
            ExportLogger.log("[BlockEntityTex] Trying to load: " + location);

            // Load using TextureLoader to avoid gamma/ICC tweaks and to honor resource packs
            return com.voxelbridge.export.texture.TextureLoader.readTexture(location);

        } catch (Exception e) {
            ExportLogger.log("[BlockEntityTex] Error loading texture " + location + ": " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage loadTextureFromResolved(BlockEntityTextureResolver.ResolvedTexture textureRes, ResourceLocation location) {
        if (textureRes.isAtlasTexture()) {
            TextureAtlasSprite sprite = textureRes.sprite();
            if (sprite != null) {
                ExportLogger.log("[BlockEntityTex] Loading atlas sprite " + sprite.contents().name() +
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
            ExportLogger.log("[BlockEntityTex] Error loading atlas sprite " + sprite.contents().name() + ": " + e.getMessage());
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
     * Packs all registered BlockEntity textures into an atlas (ATLAS mode only).
     * This must be called after exportTextures() and before glTF write.
     */
    public static void packIntoAtlas(ExportContext ctx, Path outDir) throws java.io.IOException {
        if (registeredTextures.isEmpty()) {
            ExportLogger.log("[BlockEntityTex] No textures to pack into atlas");
            return;
        }

        ExportLogger.log("[BlockEntityTex] Packing " + registeredTextures.size() + " textures into atlas");

        int atlasSize = com.voxelbridge.config.ExportRuntimeConfig.getAtlasSize().getSize();
        Path atlasDir = outDir.resolve("textures/blockentity_atlas");
        java.nio.file.Files.createDirectories(atlasDir);

        // Pack base channel to determine placements
        com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker packer =
            new com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker(atlasSize, false);
        for (Map.Entry<String, ResourceLocation> entry : registeredTextures.entrySet()) {
            String spriteKey = entry.getKey();
            if (spriteKey.endsWith("_n") || spriteKey.endsWith("_s")) continue;
            BufferedImage image = textureCache.get(entry.getValue());
            if (image == null) continue;
            packer.addTexture(spriteKey, image);
        }
        Map<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> basePlacements =
            packer.pack(atlasDir, "blockentity_atlas_");

        // Record base placements and material paths
        Map<Integer, Integer> pageToUdim = new java.util.HashMap<>();
        for (Map.Entry<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> entry : basePlacements.entrySet()) {
            String spriteKey = entry.getKey();
            var p = entry.getValue();
            pageToUdim.putIfAbsent(p.page(), p.udim());
            ExportContext.BlockEntityAtlasPlacement placement = new ExportContext.BlockEntityAtlasPlacement(
                p.page(), p.udim(), p.x(), p.y(), p.width(), p.height(), atlasSize
            );
            ctx.getBlockEntityAtlasPlacements().put(spriteKey, placement);
            String atlasPath = "textures/blockentity_atlas/blockentity_atlas_" + p.udim() + ".png";
            ctx.getMaterialPaths().put(spriteKey, atlasPath);
        }

        // Generate PBR channels aligned to base placements
        packChannel(ctx, atlasDir, atlasSize, "blockentity_atlas_n_", Channel.NORMAL, basePlacements, pageToUdim);
        packChannel(ctx, atlasDir, atlasSize, "blockentity_atlas_s_", Channel.SPECULAR, basePlacements, pageToUdim);
    }

    /**
     * Clears the texture cache (call at start of each export).
     */
    public static void clear() {
        textureCache.clear();
        registeredTextures.clear();
    }

    private static String normalKey(String baseKey) {
        return baseKey + "_n";
    }

    private static String specKey(String baseKey) {
        return baseKey + "_s";
    }

    private enum Channel { BASE, NORMAL, SPECULAR }

    private static void packChannel(ExportContext ctx, Path atlasDir, int atlasSize, String prefix, Channel channel,
                                    Map<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> basePlacements,
                                    Map<Integer, Integer> pageToUdim) throws java.io.IOException {
        int fillColor = switch (channel) {
            case NORMAL -> DEFAULT_NORMAL;
            case SPECULAR -> DEFAULT_SPEC;
            default -> 0x00000000;
        };

        // Build page images aligned to base placements
        java.util.Map<Integer, BufferedImage> pageImages = new java.util.HashMap<>();

        for (Map.Entry<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> entry : basePlacements.entrySet()) {
            String baseKey = entry.getKey();
            com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement p = entry.getValue();
            String targetKey = switch (channel) {
                case NORMAL -> normalKey(baseKey);
                case SPECULAR -> specKey(baseKey);
                default -> baseKey;
            };

            ResourceLocation targetLoc = registeredTextures.get(targetKey);
            BufferedImage src = targetLoc != null ? textureCache.get(targetLoc) : null;
            // If PBR channel missing or not cached, use default fill (do NOT fall back to albedo)
            if (src == null) {
                src = filled(p.width(), p.height(), fillColor);
            }

            BufferedImage page = pageImages.computeIfAbsent(p.page(), k -> filled(atlasSize, atlasSize, fillColor));
            page.getGraphics().drawImage(src, p.x(), p.y(), null);

            // Update context/material paths for this channel
            int udim = pageToUdim.getOrDefault(p.page(), p.page() + 1001);
            String atlasPath = "textures/blockentity_atlas/" + prefix + udim + ".png";
            ctx.getMaterialPaths().put(targetKey, atlasPath);
            ctx.getEntityTextures().putIfAbsent(targetKey,
                new ExportContext.EntityTexture(ResourceLocation.fromNamespaceAndPath("voxelbridge", atlasPath), src.getWidth(), src.getHeight()));
            ctx.getBlockEntityAtlasPlacements().put(targetKey,
                new ExportContext.BlockEntityAtlasPlacement(p.page(), udim, p.x(), p.y(), p.width(), p.height(), atlasSize));
        }

        // Write filled pages
        for (Map.Entry<Integer, BufferedImage> e : pageImages.entrySet()) {
            int page = e.getKey();
            int udim = pageToUdim.getOrDefault(page, page + 1001);
            Path target = atlasDir.resolve(prefix + udim + ".png");
            javax.imageio.ImageIO.write(e.getValue(), "png", target.toFile());
        }
    }

    private static void logBerProbe(ExportContext ctx, String msg) {
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("berprobelog.txt");
            Files.writeString(file,
                LocalDateTime.now() + " " + msg + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
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
            logBerProbe(ctx, "[BER-PBR] atlas normal try " + atlasNormal + " uv[" + u0 + "," + u1 + "][" + v0 + "," + v1 + "] for " + spriteKey);
            BufferedImage atlasImg = TextureLoader.readTexture(atlasNormal);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                ResourceLocation genLoc = generatedLocation(normalKey(spriteKey));
                textureCache.put(genLoc, cropped);
                registeredTextures.put(normalKey(spriteKey), genLoc);
                ctx.cacheSpriteImage(normalKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(genLoc, cropped.getWidth(), cropped.getHeight()));
                ExportLogger.log("[BlockEntityTex][PBR] Cropped normal from atlas " + atlasNormal + " for " + spriteKey);
                logBerProbe(ctx, "[BER-PBR] atlas normal cropped " + atlasNormal + " size " + cropped.getWidth() + "x" + cropped.getHeight());
            } else {
                logBerProbe(ctx, "[BER-PBR] atlas normal miss " + atlasNormal + " (read=" + (atlasImg != null) + ") for " + spriteKey);
            }
        }
        // Specular
        if (ctx.getCachedSpriteImage(specKey(spriteKey)) == null) {
            logBerProbe(ctx, "[BER-PBR] atlas spec try " + atlasSpec + " uv[" + u0 + "," + u1 + "][" + v0 + "," + v1 + "] for " + spriteKey);
            BufferedImage atlasImg = TextureLoader.readTexture(atlasSpec);
            BufferedImage cropped = crop(atlasImg, u0, u1, v0, v1);
            if (cropped != null) {
                ResourceLocation genLoc = generatedLocation(specKey(spriteKey));
                textureCache.put(genLoc, cropped);
                registeredTextures.put(specKey(spriteKey), genLoc);
                ctx.cacheSpriteImage(specKey(spriteKey), cropped);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(genLoc, cropped.getWidth(), cropped.getHeight()));
                ExportLogger.log("[BlockEntityTex][PBR] Cropped specular from atlas " + atlasSpec + " for " + spriteKey);
                logBerProbe(ctx, "[BER-PBR] atlas spec cropped " + atlasSpec + " size " + cropped.getWidth() + "x" + cropped.getHeight());
            } else {
                logBerProbe(ctx, "[BER-PBR] atlas spec miss " + atlasSpec + " (read=" + (atlasImg != null) + ") for " + spriteKey);
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
            logBerProbe(ctx, "[BER-PBR] sibling normal try " + sibNormal + " for " + spriteKey);
            BufferedImage img = TextureLoader.readTexture(sibNormal);
            if (img != null) {
                textureCache.put(sibNormal, img);
                registeredTextures.put(normalKey(spriteKey), sibNormal);
                ctx.cacheSpriteImage(normalKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(normalKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_n.png");
                ctx.getEntityTextures().putIfAbsent(normalKey(spriteKey),
                    new ExportContext.EntityTexture(sibNormal, img.getWidth(), img.getHeight()));
                logBerProbe(ctx, "[BER-PBR] sibling normal hit: " + sibNormal);
            } else {
                logBerProbe(ctx, "[BER-PBR] sibling normal miss " + sibNormal + " for " + spriteKey);
            }
        }

        if (needSpec) {
            ResourceLocation sibSpec = appendSuffix(pngBase, "_s");
            logBerProbe(ctx, "[BER-PBR] sibling spec try " + sibSpec + " for " + spriteKey);
            BufferedImage img = TextureLoader.readTexture(sibSpec);
            if (img != null) {
                textureCache.put(sibSpec, img);
                registeredTextures.put(specKey(spriteKey), sibSpec);
                ctx.cacheSpriteImage(specKey(spriteKey), img);
                ctx.getMaterialPaths().putIfAbsent(specKey(spriteKey),
                    "entity_textures/" + safe(spriteKey) + "_s.png");
                ctx.getEntityTextures().putIfAbsent(specKey(spriteKey),
                    new ExportContext.EntityTexture(sibSpec, img.getWidth(), img.getHeight()));
                logBerProbe(ctx, "[BER-PBR] sibling spec hit: " + sibSpec);
            } else {
                logBerProbe(ctx, "[BER-PBR] sibling spec miss " + sibSpec + " for " + spriteKey);
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
            ExportLogger.log("[BlockEntityTex][WARN] Crop failed: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage filled(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        java.util.Arrays.fill(row, argb);
        for (int y = 0; y < h; y++) {
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    private static String safe(String s) {
        return s.replace(':', '_').replace('/', '_');
    }
}
