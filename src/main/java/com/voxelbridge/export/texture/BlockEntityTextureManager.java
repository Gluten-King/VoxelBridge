package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityTextureResolver;
import com.voxelbridge.util.ExportLogger;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        // 1. Collect textures to pack
        int atlasSize = com.voxelbridge.config.ExportRuntimeConfig.getAtlasSize().getSize();
        com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker packer =
            new com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker(atlasSize, false);

        for (Map.Entry<String, ResourceLocation> entry : registeredTextures.entrySet()) {
            String spriteKey = entry.getKey();
            ResourceLocation pngLocation = entry.getValue();

            BufferedImage image = textureCache.get(pngLocation);
            if (image == null) {
                ExportLogger.log("[BlockEntityTex][WARN] Texture not in cache: " + spriteKey);
                continue;
            }

            packer.addTexture(spriteKey, image);
            ExportLogger.log("[BlockEntityTex] Added to packer: " + spriteKey + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        }

        // 2. Pack into atlas
        Path atlasDir = outDir.resolve("textures/blockentity_atlas");
        java.nio.file.Files.createDirectories(atlasDir);

        java.util.Map<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> placements =
            packer.pack(atlasDir, "blockentity_atlas_");

        // 3. Record placements in context and update material paths
        for (Map.Entry<String, com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement> entry : placements.entrySet()) {
            String spriteKey = entry.getKey();
            com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker.Placement p = entry.getValue();

            // Create placement info
            ExportContext.BlockEntityAtlasPlacement placement = new ExportContext.BlockEntityAtlasPlacement(
                p.page(), p.udim(), p.x(), p.y(), p.width(), p.height(), atlasSize
            );
            ctx.getBlockEntityAtlasPlacements().put(spriteKey, placement);

            // Update material path to point to atlas
            String atlasPath = "textures/blockentity_atlas/blockentity_atlas_" + p.udim() + ".png";
            ctx.getMaterialPaths().put(spriteKey, atlasPath);

            ExportLogger.log(String.format("[BlockEntityTex] Placed %s in atlas page %d at (%d,%d) size (%dx%d) -> %s",
                spriteKey, p.page(), p.x(), p.y(), p.width(), p.height(), atlasPath));
            ExportLogger.log(String.format("[BlockEntityTex]   UV bounds: [%.4f,%.4f]-[%.4f,%.4f]",
                placement.u0(), placement.v0(), placement.u1(), placement.v1()));
        }

        ExportLogger.log("[BlockEntityTex] Atlas packing complete, " + placements.size() + " textures packed");
    }

    /**
     * Clears the texture cache (call at start of each export).
     */
    public static void clear() {
        textureCache.clear();
        registeredTextures.clear();
    }

    private static String safe(String s) {
        return s.replace(':', '_').replace('/', '_');
    }
}
