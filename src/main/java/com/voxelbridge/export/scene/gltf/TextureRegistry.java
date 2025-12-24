package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.TextureExportRegistry;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Texture;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages exporting sprite textures and tracks glTF texture indices.
 */
final class TextureRegistry {
    private final TextureExportRegistry exportRegistry;
    private final Map<String, String> spriteRelativePaths = new HashMap<>();
    private final Map<String, Integer> spriteTextureIndices = new HashMap<>();

    TextureRegistry(ExportContext ctx, Path outDir) {
        this.exportRegistry = new TextureExportRegistry(ctx, outDir);
    }

    void ensureSpriteExport(String spriteKey) {
        if (spriteRelativePaths.containsKey(spriteKey)) {
            return;
        }
        String rel = exportRegistry.ensureSpriteExport(spriteKey);
        if (rel != null) {
            spriteRelativePaths.put(spriteKey, rel);
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
}
