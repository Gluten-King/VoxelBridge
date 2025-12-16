package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.AnimatedFrameSet;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.texture.TextureRepository;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Texture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages exporting sprite textures and tracks glTF texture indices.
 */
final class TextureRegistry {
    private final ExportContext ctx;
    private final Path texturesDir;
    private final Map<String, String> spriteRelativePaths = new HashMap<>();
    private final Map<String, Integer> spriteTextureIndices = new HashMap<>();
    private final TextureRepository repo;
    private final java.util.Set<String> exportedAnimated = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    TextureRegistry(ExportContext ctx, Path outDir) {
        this.ctx = ctx;
        this.texturesDir = outDir.resolve("textures");
        this.repo = ctx.getTextureRepository();
    }

    void ensureSpriteExport(String spriteKey) {
        // Handle BlockEntity and Entity textures separately
        if (spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:") || spriteKey.startsWith("base:")) {
            if (spriteRelativePaths.containsKey(spriteKey)) {
                return;
            }
            // Always use the path from ctx.getMaterialPaths() if available
            String path = ctx.getMaterialPaths().get(spriteKey);
            if (path != null) {
                spriteRelativePaths.put(spriteKey, path);
                return;
            }
            // Repository hit via registered location
            ResourceLocation registered = com.voxelbridge.export.texture.BlockEntityTextureManager.getRegisteredLocation(ctx, spriteKey);
            if (registered != null && repo.get(registered) != null) {
                spriteRelativePaths.put(spriteKey, com.voxelbridge.export.texture.BlockEntityTextureManager.getTextureFilename(spriteKey));
                return;
            }
            // Fallback to generated filename (should not happen in normal flow)
            String filename = com.voxelbridge.export.texture.BlockEntityTextureManager.getTextureFilename(spriteKey);
            spriteRelativePaths.put(spriteKey, filename);
            return;
        }

        // If atlas mode and placement exists, prefer atlas path (overwrites previous)
        boolean isAnimated = ExportRuntimeConfig.isAnimationEnabled() && repo.hasAnimation(spriteKey);
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS && !isAnimated) {
            String path = ctx.getMaterialPaths().get(spriteKey);
            if (path != null) {
                spriteRelativePaths.put(spriteKey, path);
                return;
            }
        }

        if (spriteRelativePaths.containsKey(spriteKey)) {
            return;
        }

        // Fallback: individual texture export
        try {
            Files.createDirectories(texturesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String safe = spriteKey.replace(':', '_').replace('/', '_');
        Path png = texturesDir.resolve(safe + ".png");
        spriteRelativePaths.put(spriteKey, "textures/" + png.getFileName().toString());
        if (!Files.exists(png)) {
            ResourceLocation pngRes = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            BufferedImage image = repo.get(pngRes);
            if (image == null) {
                image = ctx.getCachedSpriteImage(spriteKey);
            }
            if (image == null) {
                image = TextureLoader.readTexture(pngRes, ExportRuntimeConfig.isAnimationEnabled());
                if (image != null) {
                    repo.put(pngRes, spriteKey, image);
                }
            }

            if (image == null) {
                throw new IllegalStateException("Failed to resolve texture for spriteKey=" + spriteKey);
            }
            AnimatedFrameSet framesForWrite = null;
            if (ExportRuntimeConfig.isAnimationEnabled()) {
                framesForWrite = repo.getAnimation(spriteKey);
                if (framesForWrite == null) {
                    framesForWrite = AnimatedTextureHelper.extractAndStore(spriteKey, image, repo);
                }
                if (framesForWrite != null && !framesForWrite.isEmpty()) {
                    image = framesForWrite.frames().get(0);
                }
            }
            try {
                ImageIO.write(image, "PNG", png.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 导出动画帧序列（可选），并同步 PBR
        if (ExportRuntimeConfig.isAnimationEnabled()) {
            AnimatedFrameSet frames = repo.getAnimation(spriteKey);
            if (frames == null) {
                frames = AnimatedTextureHelper.extractAndStore(spriteKey, repo.getBySpriteKey(spriteKey), repo);
            }
            if (frames != null && !frames.isEmpty()) {
                // Animated frames are exported centrally in TextureAtlasManager.exportAllDetectedAnimations.
                // Avoid duplicate exports here.
            }
        }
    }

    synchronized int ensureSpriteTexture(String spriteKey,
                                         List<Texture> textures,
                                         List<Image> images) {
        ensureSpriteExport(spriteKey);
        return spriteTextureIndices.computeIfAbsent(spriteKey, key -> {
            String rel = spriteRelativePaths.get(key);
            Image image = new Image();
            image.setUri(rel);
            images.add(image);
            Texture texture = new Texture();
            texture.setSource(images.size() - 1);
            texture.setSampler(0);
            textures.add(texture);
            return textures.size() - 1;
        });
    }

    private AnimatedFrameSet ensurePbrAnimation(String spriteKey) {
        AnimatedFrameSet set = repo.getAnimation(spriteKey);
        if (set != null) return set;
        BufferedImage pbrImg = repo.getBySpriteKey(spriteKey);
        if (pbrImg != null) {
            return AnimatedTextureHelper.extractAndStore(spriteKey, pbrImg, repo);
        }
        return null;
    }

    private String safe(String spriteKey) {
        return spriteKey.replace(':', '_').replace('/', '_');
    }
}
