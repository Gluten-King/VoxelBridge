package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

public final class TextureExportPipeline {

    private TextureExportPipeline() {}

    public static void build(ExportContext ctx, Path outDir, Iterable<String> spriteKeys) throws IOException {
        if (spriteKeys != null) {
            for (String spriteKey : spriteKeys) {
                TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            }
        }
        TextureAtlasManager.generateAllAtlases(ctx, outDir);
        ColorMapManager.generateColorMaps(ctx, outDir);

        TextureExportRegistry exportRegistry = new TextureExportRegistry(ctx, outDir);
        exportRegistry.exportSprites(ctx.getMaterialPaths().keySet());
        exportRegistry.exportSprites(ctx.getEntityTextures().keySet());

        if (ExportRuntimeConfig.isAnimationEnabled()) {
            java.util.Set<String> animationWhitelist = new HashSet<>();
            animationWhitelist.addAll(ctx.getAtlasBook().keySet());
            animationWhitelist.addAll(ctx.getCachedSpriteKeys());
            TextureAtlasManager.exportDetectedAnimations(ctx, outDir, animationWhitelist);
        }
    }
}
