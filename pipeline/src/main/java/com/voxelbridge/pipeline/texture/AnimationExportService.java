package com.voxelbridge.pipeline.texture;

import com.voxelbridge.core.texture.AnimatedFrameSet;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.texture.AnimationExporter;
import com.voxelbridge.export.texture.PbrImages;
import com.voxelbridge.export.texture.TexturePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Version-independent animation selection, fallback, and output coordination. */
public final class AnimationExportService {
    private AnimationExportService() {}

    public static Result export(TextureRepository repository,
                                Path outputDirectory,
                                Set<String> whitelist,
                                Map<String, String> materialPaths,
                                MetadataSource metadataSource) throws IOException {
        Path animationDirectory = outputDirectory.resolve("textures").resolve("animated");
        Files.createDirectories(animationDirectory);
        int exported = 0;
        for (String spriteKey : repository.getAnimatedCache().keySet()) {
            if (isPbrCompanion(spriteKey) || !included(spriteKey, whitelist)) continue;
            AnimatedFrameSet frames = repository.getAnimation(spriteKey);
            if (frames == null || frames.isEmpty()) continue;

            AnimatedFrameSet normalFrames = PbrImages.matchOrDefault(
                repository.getAnimation(spriteKey + "_n"), frames, PbrImages.DEFAULT_NORMAL_COLOR);
            AnimatedFrameSet specularFrames = PbrImages.matchOrDefault(
                repository.getAnimation(spriteKey + "_s"), frames, PbrImages.DEFAULT_SPECULAR_COLOR);
            String baseName = TexturePathResolver.animationBaseName(spriteKey);
            AnimationExporter.exportAnimation(
                animationDirectory,
                baseName,
                frames,
                normalFrames,
                specularFrames,
                metadataSource == null ? null : metadataSource.read(spriteKey));

            String relativeDirectory = "textures/animated/" + baseName + "/";
            materialPaths.put(spriteKey, relativeDirectory + baseName + "_000.png");
            if (normalFrames != null && !normalFrames.isEmpty()) {
                materialPaths.put(spriteKey + "_n", relativeDirectory + baseName + "_000_n.png");
            }
            if (specularFrames != null && !specularFrames.isEmpty()) {
                materialPaths.put(spriteKey + "_s", relativeDirectory + baseName + "_000_s.png");
            }
            exported++;
        }
        return new Result(exported, repository.getAnimatedCache().size());
    }

    private static boolean included(String spriteKey, Set<String> whitelist) {
        return whitelist == null || whitelist.isEmpty() || whitelist.contains(spriteKey);
    }

    private static boolean isPbrCompanion(String spriteKey) {
        return spriteKey.endsWith("_n") || spriteKey.endsWith("_s");
    }

    @FunctionalInterface
    public interface MetadataSource {
        String read(String spriteKey);
    }

    public record Result(int exportedAnimations, int discoveredAnimations) {}
}
