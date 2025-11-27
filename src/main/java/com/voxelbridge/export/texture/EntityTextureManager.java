package com.voxelbridge.export.texture;

import com.voxelbridge.VoxelBridge;
import com.voxelbridge.export.ExportContext;
import com.mojang.blaze3d.platform.NativeImage;
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
        String key = "entity:" + texture;
        String materialName = ctx.getMaterialNameForSprite(key);
        String relativePath = ctx.getMaterialPaths()
                .computeIfAbsent(key, k -> "entity_textures/" + safe(texture.toString()) + ".png");

        ctx.getEntityTextures().computeIfAbsent(key, k -> loadTextureInfo(ctx, texture));
        return new TextureHandle(key, materialName, relativePath, texture);
    }

    public static void dumpAll(ExportContext ctx, Path outDir) throws IOException {
        Minecraft mc = ctx.getMc();
        System.out.println("[EntityTextureManager] Dumping " + ctx.getEntityTextures().size() + " entity textures");
        System.out.println("[EntityTextureManager] Generated textures: " + ctx.getGeneratedEntityTextures().size());
        System.out.println("[EntityTextureManager] Generated texture keys:");
        for (String key : ctx.getGeneratedEntityTextures().keySet()) {
            System.out.println("[EntityTextureManager]   - " + key);
        }

        writeGeneratedTextures(ctx, outDir);

        Map<String, BufferedImage> generated = ctx.getGeneratedEntityTextures();
        for (var entry : ctx.getEntityTextures().entrySet()) {
            String spriteKey = entry.getKey();
            ExportContext.EntityTexture texture = entry.getValue();
            String relativePath = ctx.getMaterialPaths().get(spriteKey);

            if (spriteKey.contains("banner") || spriteKey.contains("base")) {
                System.out.println("[EntityTextureManager] Processing: " + spriteKey);
                System.out.println("[EntityTextureManager]   relativePath: " + relativePath);
                System.out.println("[EntityTextureManager]   has generated: " + generated.containsKey(spriteKey));
            }

            if (generated.containsKey(spriteKey)) {
                continue;
            }

            if (relativePath == null) {
                if (spriteKey.contains("banner") || spriteKey.contains("base")) {
                    System.out.println("[EntityTextureManager]   SKIPPED: relativePath is null");
                }
                continue;
            }
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            BufferedImage generatedImage = generated.get(spriteKey);
            if (generatedImage != null) {
                ImageIO.write(generatedImage, "png", target.toFile());
                if (spriteKey.contains("banner") || spriteKey.contains("base")) {
                    System.out.println("[EntityTextureManager]   SAVED generated image to: " + target);
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
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());

            ctx.getEntityTextures().computeIfAbsent(spriteKey,
                    k -> new ExportContext.EntityTexture(generatedLocation(spriteKey), image.getWidth(), image.getHeight()));

            if (spriteKey.contains("banner") || spriteKey.contains("base")) {
                System.out.println("[EntityTextureManager] Processing (generated cache): " + spriteKey);
                System.out.println("[EntityTextureManager]   relativePath: " + relativePath);
                System.out.println("[EntityTextureManager]   SAVED generated image to: " + target);
            }
        }
    }

    private static ExportContext.EntityTexture loadTextureInfo(ExportContext ctx, ResourceLocation texture) {
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
            System.err.printf("[VoxelBridge][WARN] Failed to read entity texture %s: %s%n", texture, e.getMessage());
            return new ExportContext.EntityTexture(texture, DEFAULT_TEX_SIZE, DEFAULT_TEX_SIZE);
        }
    }

    private static ResourceLocation resolveTexturePath(ResourceLocation texture) {
        String path = texture.getPath();
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
