package com.voxelbridge.export.texture;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.export.texture.PngjWriter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Utility for generating PBR (Physically Based Rendering) atlas pages.
 *
 * <p>This class provides shared logic for creating aligned PBR atlases (normal and specular maps)
 * that match the layout of a base albedo atlas. It handles:
 * <ul>
 *   <li>Creating atlas pages filled with default PBR colors</li>
 *   <li>Placing PBR textures at positions matching the base atlas layout</li>
 *   <li>Filling missing PBR textures with appropriate default values</li>
 *   <li>Writing atlas pages as UDIM-tiled PNG files</li>
 * </ul>
 *
 * <p>This utility is used by both {@link TextureAtlasManager} (for regular block textures)
 * and {@link BlockEntityTextureManager} (for block entity textures) to avoid code duplication.
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class PbrAtlasWriter {

    private PbrAtlasWriter() {}

    /**
     * Represents a texture placement within an atlas page.
     * This is a generic interface to abstract over different placement implementations.
     */
    public interface Placement {
        /** Returns the page index (0, 1, 2, ...) */
        int page();
        /** Returns the UDIM tile number (1001, 1002, ...) */
        int udim();
        /** Returns the X coordinate in pixels */
        int x();
        /** Returns the Y coordinate in pixels */
        int y();
        /** Returns the width in pixels */
        int width();
        /** Returns the height in pixels */
        int height();
    }

    /**
     * Configuration for PBR atlas generation.
     * Contains all parameters needed to generate a PBR channel atlas.
     */
    public static class PbrAtlasConfig {
        private final Path outputDir;
        private final int atlasSize;
        private final String filePrefix;
        private final int defaultColor;
        private final Set<Integer> usedPages;
        private final Map<Integer, Integer> pageToUdim;

        /**
         * Creates a new PBR atlas configuration.
         *
         * @param outputDir Directory where atlas PNG files will be written
         * @param atlasSize Size of each atlas page (width and height in pixels)
         * @param filePrefix Filename prefix for PNG files (e.g., "atlas_n_" for normals)
         * @param defaultColor ARGB color to fill when PBR texture is missing
         * @param usedPages Set of page indices that need to be generated
         * @param pageToUdim Mapping from page index to UDIM tile number
         */
        public PbrAtlasConfig(Path outputDir, int atlasSize, String filePrefix, int defaultColor,
                              Set<Integer> usedPages, Map<Integer, Integer> pageToUdim) {
            this.outputDir = outputDir;
            this.atlasSize = atlasSize;
            this.filePrefix = filePrefix;
            this.defaultColor = defaultColor;
            this.usedPages = usedPages;
            this.pageToUdim = pageToUdim;
        }

        public Path outputDir() { return outputDir; }
        public int atlasSize() { return atlasSize; }
        public String filePrefix() { return filePrefix; }
        public int defaultColor() { return defaultColor; }
        public Set<Integer> usedPages() { return usedPages; }
        public Map<Integer, Integer> pageToUdim() { return pageToUdim; }
    }

    /**
     * Generates a PBR channel atlas (normal or specular) aligned to base texture placements.
     *
     * <p>This method creates atlas pages where each texture is placed at the exact same
     * position as in the base albedo atlas. Missing PBR textures are filled with the
     * configured default color.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Create empty atlas pages filled with default color</li>
     *   <li>For each placement in the base atlas:
     *     <ol>
     *       <li>Load corresponding PBR texture using the provided loader function</li>
     *       <li>If texture exists: scale and draw to atlas page at placement coordinates</li>
     *       <li>If texture missing: leave default color (already filled)</li>
     *     </ol>
     *   </li>
     *   <li>Write all atlas pages as UDIM-tiled PNG files</li>
     * </ol>
     *
     * @param <P> The type of placement (must implement {@link Placement})
     * @param config Configuration for atlas generation (size, colors, output paths)
     * @param placements Map from sprite key to placement data (position in atlas)
     * @param textureLoader Function to load PBR texture for a given sprite key.
     *                      Should return null if texture is missing.
     * @throws IOException If file writing fails
     */
    public static <P extends Placement> void generatePbrAtlas(
            PbrAtlasConfig config,
            Map<String, P> placements,
            Function<String, BufferedImage> textureLoader) throws IOException {

        if (placements.isEmpty() || config.usedPages().isEmpty()) {
            return;
        }

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[PbrAtlasWriter] Generating PBR atlas: %s (%d pages)",
                config.filePrefix(), config.usedPages().size()));

        // Create atlas pages filled with default color
        Map<Integer, BufferedImage> pages = new HashMap<>();
        for (int pageIndex : config.usedPages()) {
            pages.put(pageIndex, createFilledPage(config.atlasSize(), config.defaultColor()));
        }

        // Place textures on their corresponding pages
        int textureCount = 0;
        int missingCount = 0;

        for (Map.Entry<String, P> entry : placements.entrySet()) {
            String spriteKey = entry.getKey();
            P placement = entry.getValue();

            // Load PBR texture for this sprite
            BufferedImage pbrTexture = textureLoader.apply(spriteKey);

            BufferedImage page = pages.get(placement.page());
            if (page == null) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[PbrAtlasWriter][WARN] No page %d for sprite %s",
                        placement.page(), spriteKey));
                continue;
            }

            if (pbrTexture != null) {
                // Scale texture to match placement dimensions
                BufferedImage scaled = scaleTexture(pbrTexture, placement.width(), placement.height());
                // Draw scaled texture to atlas page
                page.getGraphics().drawImage(scaled, placement.x(), placement.y(), null);
                textureCount++;
            } else {
                // Missing PBR texture - default color is already filled
                missingCount++;
            }
        }

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[PbrAtlasWriter] Placed %d textures, %d missing (using default fill)",
                textureCount, missingCount));

        // Write atlas pages to disk
        java.nio.file.Files.createDirectories(config.outputDir());
        for (int pageIndex : config.usedPages()) {
            int udim = config.pageToUdim().getOrDefault(pageIndex, pageIndex + 1001);
            String filename = config.filePrefix() + udim + ".png";
            Path outputPath = config.outputDir().resolve(filename);

            BufferedImage page = pages.get(pageIndex);
            if (page != null) {
                PngjWriter.write(page, outputPath);
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[PbrAtlasWriter] Wrote %s", filename));
            }
        }
    }

    /**
     * Creates a BufferedImage filled with a solid color.
     * Used to create blank atlas pages with default PBR colors.
     *
     * @param size Width and height of the image (square)
     * @param argb ARGB color value to fill
     * @return A new BufferedImage filled with the specified color
     */
    private static BufferedImage createFilledPage(int size, int argb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[size];
        java.util.Arrays.fill(row, argb);
        for (int y = 0; y < size; y++) {
            image.setRGB(0, y, size, 1, row, 0, size);
        }
        return image;
    }

    /**
     * Scales a texture to the specified dimensions using nearest-neighbor interpolation.
     * This preserves the blocky pixel art style of Minecraft textures.
     *
     * @param source The source texture to scale
     * @param targetWidth Target width in pixels
     * @param targetHeight Target height in pixels
     * @return Scaled BufferedImage, or source if dimensions already match
     */
    private static BufferedImage scaleTexture(BufferedImage source, int targetWidth, int targetHeight) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    /**
     * Adapter to convert {@link com.voxelbridge.export.ExportContext.TexturePlacement}
     * to the generic {@link Placement} interface.
     * Used by {@link TextureAtlasManager}.
     */
    public static class TexturePlacementAdapter implements Placement {
        private final com.voxelbridge.export.ExportContext.TexturePlacement placement;

        public TexturePlacementAdapter(com.voxelbridge.export.ExportContext.TexturePlacement placement) {
            this.placement = placement;
        }

        @Override public int page() { return placement.page(); }
        @Override public int udim() {
            // Calculate UDIM from page index: UDIM = 1001 + (page % 10) + (page / 10) * 10
            return 1001 + (placement.page() % 10) + (placement.page() / 10) * 10;
        }
        @Override public int x() { return placement.x(); }
        @Override public int y() { return placement.y(); }
        @Override public int width() { return placement.w(); }
        @Override public int height() { return placement.h(); }
    }

    /**
     * Adapter to convert {@link TextureAtlasPacker.Placement}
     * to the generic {@link Placement} interface.
     * Used by {@link BlockEntityTextureManager}.
     */
    public static class PackerPlacementAdapter implements Placement {
        private final TextureAtlasPacker.Placement placement;

        public PackerPlacementAdapter(TextureAtlasPacker.Placement placement) {
            this.placement = placement;
        }

        @Override public int page() { return placement.page(); }
        @Override public int udim() { return placement.udim(); }
        @Override public int x() { return placement.x(); }
        @Override public int y() { return placement.y(); }
        @Override public int width() { return placement.width(); }
        @Override public int height() { return placement.height(); }
    }
}




