package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportContext.TexturePlacement;
import com.voxelbridge.util.ExportLogger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages biome color colormap (UDIM-style pages, 4x4 slots).
 * Page size is configurable via ExportRuntimeConfig.
 */
public final class ColorMapManager {

    private static final int SLOT_SIZE = 4;

    private ColorMapManager() {}

    /**
     * Initializes reserved slots in the colormap.
     * Must be called at the beginning of export, before any color registration.
     * Reserves slot 0 for pure white color (4x4 region).
     */
    public static void initializeReservedSlots(ExportContext ctx) {
        int whiteColor = 0xFFFFFFFF;

        // Temporarily set nextColorSlot to 0 to force white into slot 0
        ctx.getNextColorSlot().set(0);
        TexturePlacement whitePlacement = registerColor(ctx, whiteColor);

        // Verify slot 0 is indeed white
        if (whitePlacement.x() != 0 || whitePlacement.y() != 0) {
            throw new IllegalStateException("White color not in slot 0! Got position: (" +
                whitePlacement.x() + ", " + whitePlacement.y() + ")");
        }

        // Other colors start from slot 1
        ctx.getNextColorSlot().set(1);

        ExportLogger.log("[ColorMap] Reserved slot 0 for white color (4x4)");
    }

    /**
     * Registers a color and returns its placement.
     */
    public static TexturePlacement registerColor(ExportContext ctx, int argb) {
        // normalize alpha to 255 for mapping
        int norm = argb | 0xFF000000;
        var map = ctx.getColorMap();
        TexturePlacement existing = map.get(norm);
        if (existing != null) return existing;

        int pageSize = com.voxelbridge.config.ExportRuntimeConfig.getAtlasSize().getSize();
        int slotsPerRow = pageSize / SLOT_SIZE;
        long slotsPerPage = (long) slotsPerRow * slotsPerRow;

        long slot = ctx.getNextColorSlot().getAndIncrement();
        long page = slot / slotsPerPage;
        long idx = slot % slotsPerPage;

        int slotRow = (int) (idx / slotsPerRow);
        int slotCol = (int) (idx % slotsPerRow);

        int x = slotCol * SLOT_SIZE;
        int y = slotRow * SLOT_SIZE;

        int tileU = (int) (page % 10);
        int tileV = (int) (page / 10);

        // Add a 0.5px margin for safety
        float u0 = tileU + (x + 0.5f) / pageSize;
        // Fix UDIM V coordinate: negate tileV to compensate for coordinate flip elsewhere
        float v0 = -tileV + (y + 0.5f) / pageSize;
        float u1 = tileU + (x + SLOT_SIZE - 0.5f) / pageSize;
        float v1 = -tileV + (y + SLOT_SIZE - 0.5f) / pageSize;

        TexturePlacement placement = new TexturePlacement((int) page, tileU, tileV, x, y, SLOT_SIZE, SLOT_SIZE, u0, v0, u1, v1, null);
        map.put(norm, placement);
        return placement;
    }

    /**
     * Generates colormap pages.
     * NOTE: Now always generates colormap for UV3 consistency, regardless of biome color mode.
     * White color is pre-reserved in slot 0 by initializeReservedSlots().
     */
    public static void generateColorMaps(ExportContext ctx, Path outDir) throws IOException {
        Path dir = outDir.resolve("textures/colormap");
        Files.createDirectories(dir);

        // compute max page
        int maxPage = 0;
        for (TexturePlacement p : ctx.getColorMap().values()) {
            maxPage = Math.max(maxPage, p.page());
        }

        int pageSize = com.voxelbridge.config.ExportRuntimeConfig.getAtlasSize().getSize();
        
        ExportLogger.log("[ColorMap] entries=" + ctx.getColorMap().size()
                + " nextSlot=" + ctx.getNextColorSlot().get()
                + " pages=" + (maxPage + 1)
                + " pageSize=" + pageSize);

        // init blank pages
        BufferedImage[] pages = new BufferedImage[maxPage + 1];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB);
        }

        // fill colors
        final int[] sampleCount = {0};
        ctx.getColorMap().forEach((color, placement) -> {
            BufferedImage img = pages[placement.page()];
            for (int dx = 0; dx < SLOT_SIZE; dx++) {
                for (int dy = 0; dy < SLOT_SIZE; dy++) {
                    img.setRGB(placement.x() + dx, placement.y() + dy, color);
                }
            }
            if (sampleCount[0] < 10) {
                sampleCount[0]++;
                ExportLogger.log(String.format("[ColorMap] sample #%d color=%08X page=%d xy=(%d,%d) uv=[(%.4f,%.4f)-(%.4f,%.4f)]",
                        sampleCount[0], color, placement.page(), placement.x(), placement.y(),
                        placement.u0(), placement.v0(), placement.u1(), placement.v1()));
            }
        });

        // write pages
        for (int i = 0; i < pages.length; i++) {
            int udim = 1001 + (i % 10) + (i / 10) * 10;
            String name = "colormap_" + udim + ".png";
            Path target = dir.resolve(name);
            ImageIO.write(pages[i], "png", target.toFile());
            ExportLogger.log("[ColorMap] page=" + i + " -> " + target);
        }
    }

    /**
     * Registers a color if not present and returns the UV coordinates for its 4x4 slot.
     * The returned array contains [u0, v0, u1, v1].
     * This is to be used for the second UV set (uv1) for biome coloring via colormap texture.
     * @param ctx The export context.
     * @param argb The color to register.
     * @return A float array containing [u0, v0, u1, v1] for the color's slot in the colormap.
     */
    public static float[] remapColorUV(ExportContext ctx, int argb) {
        TexturePlacement placement = registerColor(ctx, argb);
        return new float[] { placement.u0(), placement.v0(), placement.u1(), placement.v1() };
    }
}
