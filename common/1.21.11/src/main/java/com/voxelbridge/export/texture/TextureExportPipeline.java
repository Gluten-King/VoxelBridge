package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportProgressTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

public final class TextureExportPipeline {

    private TextureExportPipeline() {}

    public static void build(ExportContext ctx, Path outDir, Iterable<String> spriteKeys) throws IOException {
        ExportOptions options = ExportOptions.fromRuntimeConfig();
        if (spriteKeys != null) {
            for (String spriteKey : spriteKeys) {
                TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            }
        }
        TextureAtlasManager.generateAllAtlases(ctx, outDir);
        ColorMapManager.generateColorMaps(ctx.state(), outDir);

        TextureExportRegistry exportRegistry = new TextureExportRegistry(ctx, outDir);
        if (spriteKeys != null) {
            exportRegistry.exportSprites(spriteKeys);
        } else {
            exportRegistry.exportSprites(ctx.getAtlasBook().keySet());
        }
        exportRegistry.exportSprites(ctx.getEntityTextures().keySet());

        if (options.animationEnabled()) {
            java.util.Set<String> animationWhitelist = new HashSet<>();
            animationWhitelist.addAll(ctx.getAtlasBook().keySet());
            animationWhitelist.addAll(ctx.getCachedSpriteKeys());
            TextureAtlasManager.exportDetectedAnimations(ctx, outDir, animationWhitelist);
        }

        if (options.pbrDecodeEnabled()) {
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.PBR_DECODE, "Decoding PBR");
            // Reset phase percent so the atlas stage doesn't appear stuck at 100% before decode starts.
            ExportProgressTracker.setPhasePercent(0f);
            LabPbrDecoder.exportDecoded(outDir, options, ExportRuntimeConfig.getExportThreadCount(),
                ExportProgressTracker::setPhasePercent);
            ExportProgressTracker.setPhasePercent(1.0f);
        }
    }
}
