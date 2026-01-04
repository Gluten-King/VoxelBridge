package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class TextureExportRegistry {
    private final ExportContext ctx;
    private final Path texturesDir;
    private final Path outputDir;
    private final TextureRepository repo;
    private final Map<String, String> spriteRelativePaths = new HashMap<>();

    public TextureExportRegistry(ExportContext ctx, Path outDir) {
        this.ctx = ctx;
        this.outputDir = outDir;
        this.texturesDir = outDir.resolve("textures");
        this.repo = ctx.getTextureRepository();
    }

    public void exportSprites(Iterable<String> spriteKeys) {
        if (spriteKeys == null) return;
        for (String key : spriteKeys) {
            ensureSpriteExport(key);
        }
    }

    public String ensureSpriteExport(String spriteKey) {
        if (spriteKey == null) {
            return null;
        }
        String existing = spriteRelativePaths.get(spriteKey);
        if (existing != null) {
            return existing;
        }

        // Special-case transparent placeholder to avoid failing on missing disk resource
        if ("voxelbridge:transparent".equals(spriteKey)) {
            String rel = ctx.getMaterialPaths().getOrDefault(spriteKey, "textures/" + safe(spriteKey) + ".png");
            Path target = outputDir.resolve(rel);
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (!Files.exists(target)) {
                BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                try {
                    ImageIO.write(transparent, "PNG", target.toFile());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            spriteRelativePaths.put(spriteKey, rel);
            return rel;
        }

        if (isPbrSprite(spriteKey)) {
            String rel = ctx.getMaterialPaths().get(spriteKey);
            if (rel != null) {
                spriteRelativePaths.put(spriteKey, rel);
                return rel;
            }
        }

        String materialRel = ctx.getMaterialPaths().get(spriteKey);
        if (materialRel != null) {
            Path target = outputDir.resolve(materialRel);
            spriteRelativePaths.put(spriteKey, materialRel);
            if (!Files.exists(target)) {
                writeFromCaches(spriteKey, target);
            }
            return materialRel;
        }

        if (isEntityLike(spriteKey)) {
            return ensureEntityLikeExport(spriteKey);
        }

        boolean isAnimated = ExportRuntimeConfig.isAnimationEnabled() && repo.hasAnimation(spriteKey);
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS && !isAnimated) {
            String path = ctx.getMaterialPaths().get(spriteKey);
            if (path != null) {
                VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
                    "[TextureExport] spriteKey=%s using materialPath=%s (atlas reuse)",
                    spriteKey, path));
                spriteRelativePaths.put(spriteKey, path);
                return path;
            }
        }

        try {
            Files.createDirectories(texturesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String safe = safe(spriteKey);
        Path png = texturesDir.resolve(safe + ".png");
        String rel = "textures/" + png.getFileName().toString();
        spriteRelativePaths.put(spriteKey, rel);
        if (!Files.exists(png)) {
            writeFromCaches(spriteKey, png);
        }
        return rel;
    }

    private String ensureEntityLikeExport(String spriteKey) {
        String rel = ctx.getMaterialPaths().get(spriteKey);
        if (rel == null) {
            ResourceLocation registered = BlockEntityTextureManager.getRegisteredLocation(ctx, spriteKey);
            if (registered != null && repo.get(registered) != null) {
                rel = BlockEntityTextureManager.getTextureFilename(spriteKey);
            }
        }
        if (rel == null) {
            rel = TexturePathResolver.ensureEntityLikePath(ctx, spriteKey);
        }
        spriteRelativePaths.put(spriteKey, rel);

        boolean isAtlasPath = rel.startsWith("textures/atlas/");
        Path target = isAtlasPath ? outputDir.resolve(rel) : texturesDir.resolve(rel);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE)) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
                "[TextureExport][EntityLike] spriteKey=%s -> rel=%s target=%s (isAtlas=%s)",
                spriteKey, rel, target, isAtlasPath));
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (Files.exists(target) || isAtlasPath) {
            return rel;
        }

        BufferedImage image = repo.getBySpriteKey(spriteKey);
        if (image == null) {
            image = ctx.getGeneratedEntityTextures().get(spriteKey);
        }
        if (image == null) {
            ResourceLocation loc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            image = TextureLoader.readTexture(loc, ExportRuntimeConfig.isAnimationEnabled());
        }
        if (image == null) {
            VoxelBridgeLogger.error(LogModule.TEXTURE, String.format("[TextureExport][ERROR] Missing image for %s (target=%s)", spriteKey, target));
            throw new RuntimeException("Failed to resolve entity texture for " + spriteKey);
        }
        try {
            ImageIO.write(image, "PNG", target.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return rel;
    }

    private boolean isEntityLike(String spriteKey) {
        return spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:") || spriteKey.startsWith("base:");
    }

    private boolean isPbrSprite(String key) {
        return key.endsWith("_n") || key.endsWith("_s");
    }

    private String safe(String spriteKey) {
        return TexturePathResolver.safe(spriteKey);
    }

    private void writeFromCaches(String spriteKey, Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        BufferedImage image = repo.getBySpriteKey(spriteKey);
        if (image == null) {
            image = ctx.getCachedSpriteImage(spriteKey);
        }
        if (image == null) {
            ResourceLocation pngRes = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            image = TextureLoader.readTexture(pngRes, ExportRuntimeConfig.isAnimationEnabled());
            if (image != null) {
                repo.put(pngRes, spriteKey, image);
            }
        }
        if (image == null) {
            throw new IllegalStateException("Failed to resolve texture for spriteKey=" + spriteKey);
        }
        if (ExportRuntimeConfig.isAnimationEnabled()) {
            AnimatedFrameSet framesForWrite = repo.getAnimation(spriteKey);
            if (framesForWrite == null) {
                framesForWrite = AnimatedTextureHelper.extractAndStore(spriteKey, image, repo);
            }
            if (framesForWrite != null && !framesForWrite.isEmpty()) {
                image = framesForWrite.frames().get(0);
            }
        }
        try {
            ImageIO.write(image, "PNG", target.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
