package com.voxelbridge.export.texture;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;

/**
 * Fast PNG writer using PNGJ (shaded at build). Writes RGBA, compression level 0.
 */
public final class PngjWriter {
    private PngjWriter() {}

    public static void write(BufferedImage src, Path target) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb;
        if (src.getType() == BufferedImage.TYPE_INT_ARGB || src.getType() == BufferedImage.TYPE_INT_ARGB_PRE) {
            argb = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        } else {
            argb = src.getRGB(0, 0, w, h, null, 0, w);
        }

        ImageInfo info = new ImageInfo(w, h, 8, true); // RGBA 8-bit
        PngWriter png = new PngWriter(target.toFile(), info);
        try {
            png.setCompLevel(1); // low compression to shrink files while staying fast
            png.setFilterType(FilterType.FILTER_NONE); // let PNGJ choose per-row filter

            ImageLineInt line = new ImageLineInt(info);
            int[] scan = line.getScanline();
            for (int y = 0; y < h; y++) {
                int rowOff = y * w;
                int idx = 0;
                for (int x = 0; x < w; x++) {
                    int c = argb[rowOff + x];
                    scan[idx++] = (c >> 16) & 0xFF; // R
                    scan[idx++] = (c >> 8) & 0xFF;  // G
                    scan[idx++] = c & 0xFF;         // B
                    scan[idx++] = (c >>> 24) & 0xFF; // A
                }
                png.writeRow(line, y);
            }
            png.end();
        } finally {
            png.close();
        }
    }
}
