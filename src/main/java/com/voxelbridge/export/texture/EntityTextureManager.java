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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
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
        String relativePath = TexturePathResolver.ensureEntityLikePath(ctx, key);

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




