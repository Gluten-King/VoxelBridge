package com.voxelbridge.export.texture;

import com.voxelbridge.VoxelBridge;
import com.voxelbridge.export.ExportContext;
import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public final class EntityTextureManager {

    private static final int DEFAULT_TEX_SIZE = 16;

    private EntityTextureManager() {}

    public static TextureHandle register(ExportContext ctx, ResourceLocation texture) {
        texture = com.voxelbridge.util.ResourceLocationUtil.sanitize(texture.toString());
        final ResourceLocation texFinal = texture;
        String spritePath = (texFinal.getNamespace() + "/" + texFinal.getPath()).replace(':', '/');
        String key = "entity:" + spritePath;
        String materialName = ctx.getMaterialNameForSprite(key);
        String relativePath = ctx.getMaterialPaths()
                .computeIfAbsent(key, k -> "entity_textures/" + safe(spritePath) + ".png");

        ctx.getEntityTextures().computeIfAbsent(key, k -> loadTextureInfo(ctx, texFinal));

        // Ensure the texture repository has an entry for this sprite.
        var repo = ctx.getTextureRepository();
        BufferedImage cached = repo.get(texFinal);
        if (cached == null) {
            ResourceLocation pngLoc = resolveTexturePath(texFinal);
            BufferedImage img = com.voxelbridge.export.texture.TextureLoader.readTexture(pngLoc, com.voxelbridge.config.ExportRuntimeConfig.isAnimationEnabled());
            if (img != null) {
                repo.put(texFinal, key, img);
            } else {
                // Preserve mapping so later sprite cache inserts can replace it.
                repo.register(key, texFinal);
            }
        } else {
            repo.register(key, texFinal);
        }

        return new TextureHandle(key, materialName, relativePath, texture);
    }

    public static void dumpAll(ExportContext ctx, Path outDir) throws IOException {
        Minecraft mc = ctx.getMc();
        boolean logTextures = VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE);
        if (logTextures) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Dumping " + ctx.getEntityTextures().size() + " entity textures");
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Generated textures: " + ctx.getGeneratedEntityTextures().size());
            VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Generated texture keys:");
            for (String key : ctx.getGeneratedEntityTextures().keySet()) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   - " + key);
            }
        }

        writeGeneratedTextures(ctx, outDir);

        Map<String, BufferedImage> generated = ctx.getGeneratedEntityTextures();
        for (var entry : ctx.getEntityTextures().entrySet()) {
            String spriteKey = entry.getKey();
            ExportContext.EntityTexture texture = entry.getValue();
            String relativePath = ctx.getMaterialPaths().get(spriteKey);

            if (logTextures && (spriteKey.contains("banner") || spriteKey.contains("base"))) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Processing: " + spriteKey);
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   relativePath: " + relativePath);
                VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   has generated: " + generated.containsKey(spriteKey));
            }

            if (generated.containsKey(spriteKey)) {
                continue;
            }

            if (relativePath == null) {
                if (logTextures && (spriteKey.contains("banner") || spriteKey.contains("base"))) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   SKIPPED: relativePath is null");
                }
                continue;
            }
            if (relativePath.startsWith("textures/atlas/")) {
                if (logTextures && (spriteKey.contains("banner") || spriteKey.contains("base"))) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   SKIPPED: atlas path");
                }
                continue;
            }
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            BufferedImage generatedImage = generated.get(spriteKey);
            if (generatedImage != null) {
                ImageIO.write(generatedImage, "png", target.toFile());
                if (logTextures && (spriteKey.contains("banner") || spriteKey.contains("base"))) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   SAVED generated image to: " + target);
                }
                continue;
            }
            if (Files.exists(target)) {
                continue;
            }
            Optional<Resource> resource = mc.getResourceManager().getResource(resolveTexturePath(texture.location()));
            if (resource.isEmpty()) {
                continue;
            }
            Resource res = resource.get();
            try (InputStream in = res.open()) {
                Files.copy(in, target);
            }
        }
    }

    private static void writeGeneratedTextures(ExportContext ctx, Path outDir) throws IOException {
        for (var entry : ctx.getGeneratedEntityTextures().entrySet()) {
            String spriteKey = entry.getKey();
            BufferedImage image = entry.getValue();
            if (image == null) {
                continue;
            }

            String relativePath = ctx.getMaterialPaths()
                    .computeIfAbsent(spriteKey, k -> "entity_textures/generated/" + safe(spriteKey) + ".png");
            if (relativePath.startsWith("textures/atlas/")) {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE)) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Skipped generated atlas write: " + spriteKey);
                }
                continue;
            }
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());

            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                    k -> new ExportContext.EntityTexture(generatedLocation(spriteKey), image.getWidth(), image.getHeight()));

            if (spriteKey.contains("banner") || spriteKey.contains("base")) {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE)) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager] Processing (generated cache): " + spriteKey);
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   relativePath: " + relativePath);
                    VoxelBridgeLogger.info(LogModule.TEXTURE, "[EntityTextureManager]   SAVED generated image to: " + target);
                }
            }
        }
    }

    private static ExportContext.EntityTexture loadTextureInfo(ExportContext ctx, ResourceLocation texture) {
        texture = com.voxelbridge.util.ResourceLocationUtil.sanitize(texture.toString());
        Minecraft mc = ctx.getMc();
        try {
            Optional<Resource> resource = mc.getResourceManager().getResource(resolveTexturePath(texture));
            if (resource.isEmpty()) {
                return new ExportContext.EntityTexture(texture, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
            }
            Resource res = resource.get();
            try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
                return new ExportContext.EntityTexture(texture, img.getWidth(), img.getHeight());
            }
        } catch (IOException e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE, String.format("[VoxelBridge][WARN] Failed to read entity texture %s: %s", texture, e.getMessage()));
            return new ExportContext.EntityTexture(texture, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
        }
    }

    private static ResourceLocation resolveTexturePath(ResourceLocation texture) {
        String path = texture.getPath();
        // Dynamic skins (e.g., minecraft:skins/aw-*) live in TextureManager, not resources.
        if (path.startsWith("skins/") || path.startsWith("skin/")) {
            return texture;
        }
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), path);
    }

    public static TextureHandle registerGenerated(ExportContext ctx, String key, String relativePath, BufferedImage image) {
        ResourceLocation generatedLoc = generatedLocation(key);
        ctx.getGeneratedEntityTextures().putIfAbsent(key, image);
        ctx.getMaterialPaths().putIfAbsent(key, relativePath);
        ctx.getEntityTextures().putIfAbsent(key, new ExportContext.EntityTexture(generatedLoc, image.getWidth(), image.getHeight()));
        String materialName = ctx.getMaterialNameForSprite(key);
        return new TextureHandle(key, materialName, relativePath, generatedLoc);
    }

    private static ResourceLocation generatedLocation(String key) {
        return ResourceLocation.fromNamespaceAndPath(VoxelBridge.MODID, "generated/" + safe(key));
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

    public record TextureHandle(String spriteKey, String materialName, String relativePath, ResourceLocation textureLocation) {}
}




