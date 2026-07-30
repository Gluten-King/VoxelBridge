package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.texture.AnimatedFrameSet;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.texture.TextureExportPlanner;
import com.voxelbridge.export.texture.TextureExporter;
import com.voxelbridge.export.texture.TexturePathPlanner;
import com.voxelbridge.export.texture.AtlasBuilder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

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

        if (TextureExportPlanner.isPbrSprite(spriteKey)) {
            String rel = ctx.getMaterialPaths().get(spriteKey);
            if (rel != null) {
                spriteRelativePaths.put(spriteKey, rel);
                return rel;
            }
        }

        if (TextureExportPlanner.isEntityLike(spriteKey)) {
            return ensureEntityLikeExport(spriteKey);
        }

        boolean isAnimated = ExportRuntimeConfig.isAnimationEnabled() && repo.hasAnimation(spriteKey);
        String path = ctx.getMaterialPaths().get(spriteKey);
        if (path != null && (!isAnimated || path.startsWith("textures/animated/"))) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
                "[TextureExport] spriteKey=%s using materialPath=%s (reuse)",
                spriteKey, path));
            spriteRelativePaths.put(spriteKey, path);
            return path;
        }

        try {
            Files.createDirectories(texturesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String rel = TextureExportPlanner.resolveSpritePath(ctx.getMaterialPaths(), spriteKey);
        String safe = TexturePathResolver.safe(spriteKey);
        Path png = texturesDir.resolve(safe + ".png");
        spriteRelativePaths.put(spriteKey, rel);
        if (!Files.exists(png)) {
            String pngKey = ctx.getTextureAccess().spriteKeyToResourceKey(spriteKey);
            BufferedImage image = repo.get(pngKey);
            if (image == null) {
                image = ctx.getCachedSpriteImage(spriteKey);
            }
            if (image == null) {
                image = ctx.getTextureAccess().readTexture(pngKey, ExportRuntimeConfig.isAnimationEnabled());
                if (image != null) {
                    repo.put(pngKey, spriteKey, image);
                }
            }
            if (image == null) {
                throw new IllegalStateException("Failed to resolve texture for spriteKey=" + spriteKey);
            }
            if (ExportRuntimeConfig.isAnimationEnabled()) {
                AnimatedFrameSet framesForWrite = repo.getAnimation(spriteKey);
                if (framesForWrite == null) {
                    framesForWrite = AnimatedTextureHelper.extractAndStore(ctx, spriteKey, image, repo);
                }
                if (framesForWrite != null && !framesForWrite.isEmpty()) {
                    image = framesForWrite.frames().get(0);
                }
            }
            try {
                TextureExporter.writePng(image, png);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return rel;
    }

    private String ensureEntityLikeExport(String spriteKey) {
        String rel = ctx.getMaterialPaths().get(spriteKey);
        if (rel == null) {
            String registered = BlockEntityTextureManager.getRegisteredLocation(ctx, spriteKey);
            if (registered != null && repo.get(registered) != null) {
                rel = BlockEntityTextureManager.getTextureFilename(spriteKey);
            }
        }
        if (rel == null) {
            rel = TextureExportPlanner.resolveEntityLikePath(
                ctx.getMaterialPaths(), spriteKey, ExportOptions.fromRuntimeConfig());
        }
        spriteRelativePaths.put(spriteKey, rel);
        ctx.getMaterialPaths().put(spriteKey, rel);

        boolean isAbsoluteRel = rel.startsWith("textures/");
        Path target = isAbsoluteRel ? outputDir.resolve(rel) : texturesDir.resolve(rel);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE)) {
            VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
                "[TextureExport][EntityLike] spriteKey=%s -> rel=%s target=%s (isAbsolute=%s)",
                spriteKey, rel, target, isAbsoluteRel));
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (Files.exists(target)) {
            return rel;
        }

        BufferedImage image = repo.getBySpriteKey(spriteKey);
        if (image == null) {
            image = ctx.getGeneratedEntityTextures().get(spriteKey);
        }
        if (image == null) {
            String resourceKey = ctx.getTextureAccess().spriteKeyToResourceKey(spriteKey);
            image = ctx.getTextureAccess().readTexture(resourceKey, ExportRuntimeConfig.isAnimationEnabled());
        }
        if (image == null) {
            VoxelBridgeLogger.error(LogModule.TEXTURE, String.format("[TextureExport][ERROR] Missing image for %s (target=%s)", spriteKey, target));
            BufferedImage placeholder = AtlasBuilder.createMissingTexture();
            try {
                TextureExporter.writePng(placeholder, target);
            } catch (IOException e) {
                throw new RuntimeException("Failed to resolve entity texture for " + spriteKey, e);
            }
            return rel;
        }
        try {
            TextureExporter.writePng(image, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return rel;
    }

    private String safe(String spriteKey) {
        return TexturePathResolver.safe(spriteKey);
    }
}
