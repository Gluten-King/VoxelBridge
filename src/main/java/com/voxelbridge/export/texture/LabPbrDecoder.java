package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;

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

        Set<String> udimSet = new HashSet<>();
        udimSet.addAll(normalPages.keySet());
        udimSet.addAll(specPages.keySet());
        if (udimSet.isEmpty()) {
            return;
        }

        int threadCount = com.voxelbridge.config.ExportRuntimeConfig.getExportThreadCount();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, threadCount));
        try {
            java.util.List<Future<?>> futures = new ArrayList<>(udimSet.size());
            for (String udim : udimSet) {
                futures.add(executor.submit(() -> {
                    try {
                        BufferedImage normal = readImage(normalPages.get(udim));
                        if (normal != null) {
                            PngjWriter.write(decodeNormal(normal), atlasDir.resolve("atlas_normal_" + udim + ".png"));
                            PngjWriter.write(extractChannel(normal, Channel.BLUE), atlasDir.resolve("atlas_ao_" + udim + ".png"));
                            PngjWriter.write(extractChannel(normal, Channel.ALPHA), atlasDir.resolve("atlas_height_" + udim + ".png"));
                        }

                        BufferedImage spec = readImage(specPages.get(udim));
                        if (spec != null) {
                            BufferedImage albedo = readImage(albedoPages.get(udim));
                            if (albedo != null && (albedo.getWidth() != spec.getWidth() || albedo.getHeight() != spec.getHeight())) {
                                albedo = resizeTo(albedo, spec.getWidth(), spec.getHeight());
                            }
                            PngjWriter.write(decodeRoughness(spec), atlasDir.resolve("atlas_roughness_" + udim + ".png"));
                            PngjWriter.write(decodeMetallic(spec), atlasDir.resolve("atlas_metallic_" + udim + ".png"));
                            PngjWriter.write(decodeSss(spec), atlasDir.resolve("atlas_sss_" + udim + ".png"));
                            PngjWriter.write(decodeEmissive(albedo, spec), atlasDir.resolve("atlas_emissive_" + udim + ".png"));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("LabPBR decode failed", cause);
        } finally {
            executor.shutdown();
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
        IntRaster src = getIntRaster(img);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, outIdx++) {
                int argb = src.data[srcIdx];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                double nx = NORMAL_COMPONENT_LUT[r];
                double ny = NORMAL_COMPONENT_LUT[g];
                double nz2 = 1.0 - nx * nx - ny * ny;
                double nz = nz2 > 0.0 ? Math.sqrt(nz2) : 0.0;
                int nr = clamp255((nx + 1.0) * 0.5 * 255.0);
                int ng = clamp255((ny + 1.0) * 0.5 * 255.0);
                int nb = clamp255((nz + 1.0) * 0.5 * 255.0);
                out[outIdx] = (0xFF << 24) | (nr << 16) | (ng << 8) | nb;
            }
            srcRowStart += src.stride;
        }
        return result;
    }

    private static BufferedImage decodeRoughness(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        IntRaster src = getIntRaster(img);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, outIdx++) {
                int r = (src.data[srcIdx] >> 16) & 0xFF;
                int v = ROUGHNESS_LUT[r];
                out[outIdx] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            srcRowStart += src.stride;
        }
        return result;
    }

    private static BufferedImage decodeMetallic(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        IntRaster src = getIntRaster(img);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, outIdx++) {
                int g = (src.data[srcIdx] >> 8) & 0xFF;
                int v = METALLIC_LUT[g];
                out[outIdx] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            srcRowStart += src.stride;
        }
        return result;
    }

    private static BufferedImage decodeEmissive(BufferedImage albedo, BufferedImage spec) {
        int w = spec.getWidth();
        int h = spec.getHeight();
        IntRaster src = getIntRaster(spec);
        int[] base = null;
        IntRaster baseRaster = null;
        if (albedo != null) {
            baseRaster = getIntRaster(albedo);
            base = baseRaster.data;
        }

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        int baseRowStart = baseRaster != null ? baseRaster.offset : 0;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            int baseIdx = baseRowStart;
            for (int x = 0; x < w; x++, srcIdx++, baseIdx++, outIdx++) {
                int a = (src.data[srcIdx] >>> 24) & 0xFF;
                int strength = a == 255 ? 0 : a;
                double s = strength / 254.0;
                if (base != null) {
                    int br = (base[baseIdx] >> 16) & 0xFF;
                    int bg = (base[baseIdx] >> 8) & 0xFF;
                    int bb = base[baseIdx] & 0xFF;
                    int r = clamp255(br * s);
                    int g = clamp255(bg * s);
                    int b = clamp255(bb * s);
                    out[outIdx] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                } else {
                    int v = clamp255(s * 255.0);
                    out[outIdx] = (0xFF << 24) | (v << 16) | (v << 8) | v;
                }
            }
            srcRowStart += src.stride;
            if (baseRaster != null) {
                baseRowStart += baseRaster.stride;
            }
        }
        return result;
    }

    private static BufferedImage decodeSss(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        IntRaster src = getIntRaster(img);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, outIdx++) {
                int g = (src.data[srcIdx] >> 8) & 0xFF;
                int b = src.data[srcIdx] & 0xFF;
                int v = (g < 230) ? SSS_LUT[b] : 0;
                out[outIdx] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            srcRowStart += src.stride;
        }
        return result;
    }

    private static BufferedImage extractChannel(BufferedImage img, Channel channel) {
        int w = img.getWidth();
        int h = img.getHeight();
        IntRaster src = getIntRaster(img);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();

        int outIdx = 0;
        int srcRowStart = src.offset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, outIdx++) {
                int argb = src.data[srcIdx];
                int v = switch (channel) {
                    case RED -> (argb >> 16) & 0xFF;
                    case GREEN -> (argb >> 8) & 0xFF;
                    case BLUE -> argb & 0xFF;
                    case ALPHA -> (argb >>> 24) & 0xFF;
                };
                out[outIdx] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            srcRowStart += src.stride;
        }
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
        PngReader reader = new PngReader(path.toFile());
        try {
            ImageInfo info = reader.imgInfo;
            int w = info.cols;
            int h = info.rows;
            int channels = info.channels;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                ImageLineInt line = (ImageLineInt) reader.readRow();
                int[] scan = line.getScanline();
                int rowOff = y * w;
                int idx = 0;
                for (int x = 0; x < w; x++) {
                    int r;
                    int g;
                    int b;
                    int a;
                    if (channels == 4) {
                        r = scan[idx++];
                        g = scan[idx++];
                        b = scan[idx++];
                        a = scan[idx++];
                    } else if (channels == 3) {
                        r = scan[idx++];
                        g = scan[idx++];
                        b = scan[idx++];
                        a = 255;
                    } else if (channels == 2) {
                        r = scan[idx++];
                        g = r;
                        b = r;
                        a = scan[idx++];
                    } else {
                        r = scan[idx++];
                        g = r;
                        b = r;
                        a = 255;
                    }
                    if (info.bitDepth == 16) {
                        r = r >> 8;
                        g = g >> 8;
                        b = b >> 8;
                        a = a >> 8;
                    }
                    out[rowOff + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return img;
        } finally {
            reader.close();
        }
    }

    private static IntRaster getIntRaster(BufferedImage img) {
        var raster = img.getRaster();
        if (raster.getDataBuffer() instanceof DataBufferInt db
            && raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm) {
            return new IntRaster(db.getData(), sm.getScanlineStride(), db.getOffset());
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] data = img.getRGB(0, 0, w, h, null, 0, w);
        return new IntRaster(data, w, 0);
    }

    private record IntRaster(int[] data, int stride, int offset) {}

    private enum Channel {
        RED, GREEN, BLUE, ALPHA
    }
}
