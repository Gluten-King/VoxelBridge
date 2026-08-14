package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Material;
import de.javagl.jgltf.impl.v2.MaterialPbrMetallicRoughness;
import de.javagl.jgltf.impl.v2.Texture;
import de.javagl.jgltf.impl.v2.TextureInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes the stable VoxelBridge material contract independently of geometry assembly. */
final class GltfMaterialWriter {
    private final ExportState state;
    private final TextureRegistry textureRegistry;
    private final ExportOptions options;

    GltfMaterialWriter(ExportState state, TextureRegistry textureRegistry, ExportOptions options) {
        this.state = state;
        this.textureRegistry = textureRegistry;
        this.options = options;
    }

    int write(
        String materialKey,
        String selectedSprite,
        boolean capturedDoubleSided,
        List<Integer> colorMapIndices,
        List<Material> materials,
        List<Texture> textures,
        List<Image> images
    ) throws IOException {
        String sprite = validatedSprite(materialKey, selectedSprite);
        int textureIndex = textureRegistry.ensureSpriteTexture(sprite, textures, images);

        Material material = new Material();
        material.setName(materialKey);
        MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
        TextureInfo textureInfo = new TextureInfo();
        textureInfo.setIndex(textureIndex);
        pbr.setBaseColorTexture(textureInfo);
        pbr.setMetallicFactor(0.0f);
        pbr.setRoughnessFactor(1.0f);
        material.setPbrMetallicRoughness(pbr);

        boolean glyph = MaterialSemantic.isGlyph(materialKey);
        material.setAlphaMode(glyph ? "BLEND" : "OPAQUE");
        material.setDoubleSided(options.forceDoubleSided() || capturedDoubleSided);

        Map<String, Object> extras = new HashMap<>();
        extras.put("voxelbridge:emissive", materialKey != null && materialKey.contains("_emissive"));
        if (glyph) extras.put("voxelbridge:glyph", true);
        if (!colorMapIndices.isEmpty()) {
            extras.put("voxelbridge:colormapTextures", colorMapIndices);
            extras.put("voxelbridge:colormapUV", 1);
        }
        material.setExtras(extras);
        materials.add(material);
        return materials.size() - 1;
    }

    private String validatedSprite(String materialKey, String selectedSprite) throws IOException {
        if (selectedSprite != null && state.getMaterialPaths().containsKey(selectedSprite)) {
            return selectedSprite;
        }
        if (state.getMaterialPaths().containsKey("voxelbridge:transparent")) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE, String.format(
                "[TextureRegistry][MaterialSprites][WARN] matKey=%s picked invalid sprite=%s, fallback to voxelbridge:transparent",
                materialKey, selectedSprite));
            return "voxelbridge:transparent";
        }
        throw new IOException("No valid texture path for material " + materialKey + " (picked=" + selectedSprite + ")");
    }
}
