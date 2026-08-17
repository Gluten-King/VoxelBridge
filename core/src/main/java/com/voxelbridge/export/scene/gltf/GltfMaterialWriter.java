package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.core.ir.RenderLayer;
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
import java.util.Locale;
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
        RenderLayer capturedLayer,
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
        RenderLayer effectiveLayer = glyph
            ? RenderLayer.TRANSLUCENT
            : resolveEffectiveLayer(capturedLayer, materialKey);
        applyAlphaMode(material, effectiveLayer);

        boolean entityLike = isEntityLikeMaterial(materialKey);
        boolean torchShell = isTorchGlowShellMaterial(materialKey);
        if (torchShell) {
            material.setDoubleSided(true);
        } else if (effectiveLayer == RenderLayer.TRANSLUCENT || entityLike) {
            material.setDoubleSided(false);
        } else {
            material.setDoubleSided(options.forceDoubleSided() || capturedDoubleSided);
        }

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

    /** Prefer the more transparent hint when one material bucket mixes layers. */
    static RenderLayer strongerLayer(RenderLayer current, RenderLayer next) {
        if (next == null || next == RenderLayer.UNKNOWN) {
            return current != null ? current : RenderLayer.UNKNOWN;
        }
        if (current == null || current == RenderLayer.UNKNOWN) return next;
        if (current == RenderLayer.TRANSLUCENT || next == RenderLayer.TRANSLUCENT) {
            return RenderLayer.TRANSLUCENT;
        }
        if (current == RenderLayer.CUTOUT || next == RenderLayer.CUTOUT) return RenderLayer.CUTOUT;
        return RenderLayer.SOLID;
    }

    private static RenderLayer resolveEffectiveLayer(RenderLayer layer, String materialKey) {
        RenderLayer effective = layer != null ? layer : RenderLayer.UNKNOWN;
        if (effective == RenderLayer.UNKNOWN) effective = inferLayerFromName(materialKey);
        if ((effective == RenderLayer.SOLID || effective == RenderLayer.UNKNOWN)
                && !isTorchGlowShellMaterial(materialKey)
                && isHardCutoutPlant(materialKey)) {
            effective = RenderLayer.CUTOUT;
        }
        if (effective == RenderLayer.TRANSLUCENT
                && isHardAlphaGlass(materialKey)
                && !isTorchGlowShellMaterial(materialKey)) {
            return RenderLayer.CUTOUT;
        }
        if (effective == RenderLayer.TRANSLUCENT && contains(materialKey, "lava")
                && !contains(materialKey, "gel")) {
            return RenderLayer.SOLID;
        }
        if (!isEntityLikeMaterial(materialKey)) return effective;
        if (effective == RenderLayer.TRANSLUCENT) return RenderLayer.CUTOUT;
        if (effective == RenderLayer.CUTOUT) {
            String lower = lower(materialKey);
            if (lower.contains("slime") || lower.contains("player")
                    || lower.contains("armor") || lower.contains("cape")
                    || lower.contains("elytra") || lower.contains("ghost")
                    || lower.contains("enderman") || lower.contains("blaze")) {
                return RenderLayer.CUTOUT;
            }
            return RenderLayer.SOLID;
        }
        return effective;
    }

    private static boolean isHardCutoutPlant(String materialKey) {
        String bare = lower(materialKey);
        if (bare.isEmpty() || isTorchGlowShellMaterial(bare)) return false;
        int slash = bare.lastIndexOf('/');
        if (slash >= 0) bare = bare.substring(slash + 1);
        int colon = bare.lastIndexOf(':');
        if (colon >= 0) bare = bare.substring(colon + 1);
        return bare.equals("bamboo")
            || bare.equals("bamboo_sapling")
            || bare.equals("sugar_cane")
            || bare.equals("dead_bush")
            || bare.equals("sweet_berry_bush")
            || bare.equals("cobweb")
            || bare.equals("short_grass")
            || bare.equals("tall_grass")
            || bare.equals("fern")
            || bare.equals("large_fern")
            || bare.startsWith("redstone_dust")
            || bare.equals("redstone_wire")
            || bare.contains("torch")
            || bare.contains("lantern")
            || (bare.contains("fire") && !bare.contains("campfire_log"));
    }

    private static boolean isTorchGlowShellMaterial(String materialKey) {
        String lower = lower(materialKey);
        return lower.endsWith("_shell") || lower.contains(":shell");
    }

    private static boolean isHardAlphaGlass(String materialKey) {
        String lower = lower(materialKey);
        return lower.contains("glass") && !lower.contains("stained") && !lower.contains("tinted");
    }

    private static boolean isEntityLikeMaterial(String materialKey) {
        String lower = lower(materialKey);
        return lower.startsWith("blockentity:")
            || lower.startsWith("entity:")
            || lower.contains("blockentity/")
            || lower.contains("entity/");
    }

    private static void applyAlphaMode(Material material, RenderLayer effective) {
        switch (effective != null ? effective : RenderLayer.UNKNOWN) {
            case TRANSLUCENT -> material.setAlphaMode("BLEND");
            case CUTOUT -> {
                material.setAlphaMode("MASK");
                material.setAlphaCutoff(0.1f);
            }
            case SOLID, UNKNOWN -> material.setAlphaMode("OPAQUE");
        }
    }

    private static RenderLayer inferLayerFromName(String materialKey) {
        String lower = lower(materialKey);
        if (lower.contains("lava")) return RenderLayer.SOLID;
        if (isTorchGlowShellMaterial(lower)) return RenderLayer.TRANSLUCENT;
        if (lower.contains("water") || lower.contains("ice")
                || lower.contains("slime") || lower.contains("honey")
                || lower.contains("stained_glass") || lower.contains("tinted_glass")
                || lower.contains("translucent") || lower.contains("nether_portal")) {
            return RenderLayer.TRANSLUCENT;
        }
        if (lower.contains("glass")) return RenderLayer.CUTOUT;
        if (lower.contains("leaves") || lower.contains("sapling")
                || lower.contains("short_grass") || lower.contains("tall_grass")
                || lower.contains("fern") || lower.contains("flower")
                || lower.contains("vine") || lower.contains("rail")
                || lower.contains("trapdoor") || lower.contains("ladder")
                || lower.contains("iron_bars") || lower.contains("chain")
                || lower.contains("cutout") || lower.contains("_overlay")
                || lower.contains("torch") || lower.contains("lantern")
                || lower.contains("redstone_dust") || lower.contains("redstone_wire")
                || lower.equals("minecraft:bamboo") || lower.endsWith(":bamboo")
                || lower.contains("bamboo_sapling") || lower.contains("sugar_cane")
                || lower.endsWith(":grass") || lower.endsWith("/grass")) {
            return RenderLayer.CUTOUT;
        }
        return RenderLayer.UNKNOWN;
    }

    static int alphaDrawOrder(Material material) {
        if (material == null || material.getAlphaMode() == null) return 0;
        if ("MASK".equalsIgnoreCase(material.getAlphaMode())) return 1;
        if ("BLEND".equalsIgnoreCase(material.getAlphaMode())) return 2;
        return 0;
    }

    private static boolean contains(String value, String part) {
        return lower(value).contains(part);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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
