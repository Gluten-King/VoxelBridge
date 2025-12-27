package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Exports decoded LabPBR channel maps from cached _n/_s textures.
 * Mirrors tools/mat.py behavior.
 */
public final class LabPbrDecoder {

    private LabPbrDecoder() {}

    public static void exportDecoded(ExportContext ctx, Path outDir) throws IOException {
        if (com.voxelbridge.config.ExportRuntimeConfig.getAtlasMode()
            != com.voxelbridge.config.ExportRuntimeConfig.AtlasMode.ATLAS) {
            return;
        }

        Path atlasDir = outDir.resolve("textures").resolve("atlas");
        if (!Files.isDirectory(atlasDir)) {
            return;
        }

        Map<String, Path> albedoPages = new HashMap<>();
        Map<String, Path> normalPages = new HashMap<>();
        Map<String, Path> specPages = new HashMap<>();

        try (var stream = Files.newDirectoryStream(atlasDir, "*.png")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith("atlas_n_") && name.endsWith(".png")) {
                    String udim = name.substring("atlas_n_".length(), name.length() - 4);
                    normalPages.put(udim, p);
                } else if (name.startsWith("atlas_s_") && name.endsWith(".png")) {
                    String udim = name.substring("atlas_s_".length(), name.length() - 4);
                    specPages.put(udim, p);
                } else if (name.startsWith("atlas_") && name.endsWith(".png")) {
                    String udim = name.substring("atlas_".length(), name.length() - 4);
                    albedoPages.put(udim, p);
                }
            }
        }

        Set<String> udims = new HashSet<>();
        udims.addAll(normalPages.keySet());
        udims.addAll(specPages.keySet());

        for (String udim : udims) {
            BufferedImage normal = readImage(normalPages.get(udim));
            if (normal != null) {
                java.util.stream.Stream.of(
                    (Runnable) () -> PngjWriter.write(decodeNormal(normal), atlasDir.resolve("atlas_normal_" + udim + ".png")),
                    (Runnable) () -> PngjWriter.write(extractChannel(normal, Channel.BLUE), atlasDir.resolve("atlas_ao_" + udim + ".png")),
                    (Runnable) () -> PngjWriter.write(extractChannel(normal, Channel.ALPHA), atlasDir.resolve("atlas_height_" + udim + ".png"))
                ).parallel().forEach(Runnable::run);
            }

            BufferedImage spec = readImage(specPages.get(udim));
            if (spec != null) {
                final BufferedImage finalAlbedo;
                BufferedImage albedoTemp = readImage(albedoPages.get(udim));
                if (albedoTemp != null && (albedoTemp.getWidth() != spec.getWidth() || albedoTemp.getHeight() != spec.getHeight())) {
                    finalAlbedo = resizeTo(albedoTemp, spec.getWidth(), spec.getHeight());
                } else {
                    finalAlbedo = albedoTemp;
                }

                java.util.stream.Stream.of(
                    (Runnable) () -> PngjWriter.write(decodeRoughness(spec), atlasDir.resolve("atlas_roughness_" + udim + ".png")),
                    (Runnable) () -> PngjWriter.write(decodeMetallic(spec), atlasDir.resolve("atlas_metallic_" + udim + ".png")),
                    (Runnable) () -> PngjWriter.write(decodeSss(spec), atlasDir.resolve("atlas_sss_" + udim + ".png")),
                    (Runnable) () -> PngjWriter.write(decodeEmissive(finalAlbedo, spec), atlasDir.resolve("atlas_emissive_" + udim + ".png"))
                ).parallel().forEach(Runnable::run);
            }
        }
    }

    private static final int[] ROUGHNESS_LUT = new int[256];
    private static final int[] METALLIC_LUT = new int[256];
    private static final int[] SSS_LUT = new int[256];
    private static final double[] NORMAL_COMPONENT_LUT = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            // Roughness
            double smoothness = i / 255.0;
            double roughness = (1.0 - smoothness) * (1.0 - smoothness);
            ROUGHNESS_LUT[i] = clamp255(roughness * 255.0);

            // Metallic
            METALLIC_LUT[i] = i >= 230 ? 255 : 0;

            // SSS (precompute the mapping for 'b' when condition is met)
            double sss = 0.0;
            if (i >= 65) {
                sss = (i - 65.0) / 190.0;
            }
            SSS_LUT[i] = clamp255(sss * 255.0);

            // Normal component (-1.0 to 1.0)
            NORMAL_COMPONENT_LUT[i] = i / 255.0 * 2.0 - 1.0;
        }
    }

    private static BufferedImage decodeNormal(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[src.length];

        java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
            int argb = src[i];
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            double nx = NORMAL_COMPONENT_LUT[r];
            double ny = NORMAL_COMPONENT_LUT[g];
            double nz2 = 1.0 - nx * nx - ny * ny;
            double nz = nz2 > 0.0 ? Math.sqrt(nz2) : 0.0;
            int nr = clamp255((nx + 1.0) * 0.5 * 255.0);
            int ng = clamp255((ny + 1.0) * 0.5 * 255.0);
            int nb = clamp255((nz + 1.0) * 0.5 * 255.0);
            out[i] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
        });

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage decodeRoughness(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[src.length];
        java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
            int r = (src[i] >> 16) & 0xFF;
            int v = ROUGHNESS_LUT[r];
            out[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        });
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage decodeMetallic(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[src.length];
        java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
            int g = (src[i] >> 8) & 0xFF;
            int v = METALLIC_LUT[g];
            out[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        });
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage decodeEmissive(BufferedImage albedo, BufferedImage spec) {
        int w = spec.getWidth();
        int h = spec.getHeight();
        int[] src = spec.getRGB(0, 0, w, h, null, 0, w);

        int[] out = new int[src.length];
        int[] base = albedo != null ? albedo.getRGB(0, 0, w, h, null, 0, w) : null;

        if (base != null) {
            java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
                int a = (src[i] >>> 24) & 0xFF;
                int strength = a == 255 ? 0 : a;
                double s = strength / 254.0;
                int br = (base[i] >> 16) & 0xFF;
                int bg = (base[i] >> 8) & 0xFF;
                int bb = base[i] & 0xFF;
                int r = clamp255(br * s);
                int g = clamp255(bg * s);
                int b = clamp255(bb * s);
                out[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            });
        } else {
            java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
                int a = (src[i] >>> 24) & 0xFF;
                int strength = a == 255 ? 0 : a;
                double s = strength / 254.0;
                int v = clamp255(s * 255.0);
                out[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            });
        }
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage decodeSss(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[src.length];
        java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
            int g = (src[i] >> 8) & 0xFF;
            int b = src[i] & 0xFF;
            int v = (g < 230) ? SSS_LUT[b] : 0;
            out[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        });
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage extractChannel(BufferedImage img, Channel channel) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[src.length];
        java.util.stream.IntStream.range(0, src.length).parallel().forEach(i -> {
            int argb = src[i];
            int v = switch (channel) {
                case RED -> (argb >> 16) & 0xFF;
                case GREEN -> (argb >> 8) & 0xFF;
                case BLUE -> argb & 0xFF;
                case ALPHA -> (argb >>> 24) & 0xFF;
            };
            out[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        });
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static BufferedImage resizeTo(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private static int clamp255(double v) {
        if (v <= 0.0) return 0;
        if (v >= 255.0) return 255;
        return (int) v;
    }

    private static BufferedImage readImage(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        return ImageIO.read(path.toFile());
    }

    private enum Channel {
        RED, GREEN, BLUE, ALPHA
    }
}
