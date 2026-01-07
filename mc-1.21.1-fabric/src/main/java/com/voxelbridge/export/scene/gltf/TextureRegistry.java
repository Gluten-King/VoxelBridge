package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Texture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages exporting sprite textures and tracks glTF texture indices.
 */
final class TextureRegistry {
    private final ExportState state;
    private final Map<String, String> spriteRelativePaths = new HashMap<>();
    private final Map<String, Integer> spriteTextureIndices = new HashMap<>();

    TextureRegistry(ExportState state) {
        this.state = state;
    }

    void ensureSpriteExport(String spriteKey) {
        if (spriteRelativePaths.containsKey(spriteKey)) {
            return;
        }
        String rel = state.getMaterialPaths().get(spriteKey);
        if (rel != null) {
            spriteRelativePaths.put(spriteKey, rel);
            return;
        }
        throw new IllegalStateException("Missing material path for spriteKey=" + spriteKey + " (texture pipeline not run?)");
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
