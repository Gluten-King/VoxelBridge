package com.voxelbridge.export.texture;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

/**
 * Generic texture atlas packer using the MaxRects (Maximum Rectangles) algorithm.
 * This implementation is based on the algorithm by Jukka Jylanki.
 *
 * <p>This packer is used by both regular block textures and block entity textures
 * to combine multiple textures into fixed-size atlas pages with UDIM tiling.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Best-fit placement strategy to minimize wasted space</li>
 *   <li>Deterministic packing order (largest textures first, then alphabetically)</li>
 *   <li>Automatic page allocation when existing pages are full</li>
 *   <li>UDIM coordinate system for multi-page atlases</li>
 * </ul>
 */
public final class TextureAtlasPacker {
    private final int atlasSize;
    private final List<TextureEntry> textures = new ArrayList<>();
    private final boolean powerOfTwo;

    /**
     * Creates a new texture atlas packer.
     *
     * @param atlasSize The size of each atlas page (width and height in pixels)
     * @param powerOfTwo Whether to enforce power-of-two dimensions (currently unused)
     */
    public TextureAtlasPacker(int atlasSize, boolean powerOfTwo) {
        this.atlasSize = atlasSize;
        this.powerOfTwo = powerOfTwo;
    }

    /**
     * Adds a texture to be packed into the atlas.
     * Call this for each texture before calling {@link #pack}.
     *
     * @param spriteKey Unique identifier for the texture
     * @param image The texture image to pack
     */
    public void addTexture(String spriteKey, BufferedImage image) {
        textures.add(new TextureEntry(spriteKey, image));
    }

    /**
     * Packs all added textures into atlas pages and writes them to disk.
     *
     * <p>Packing strategy:
     * <ol>
     *   <li>Sort textures by largest dimension (descending), then alphabetically</li>
     *   <li>Try to place each texture in the best-fitting spot in existing pages</li>
     *   <li>Allocate new pages as needed when textures don't fit</li>
     *   <li>Write all pages as PNG files with UDIM naming (e.g., prefix_1001.png)</li>
     * </ol>
     *
     * @param outputDir Directory where atlas pages will be written
     * @param prefix Filename prefix for atlas pages (e.g., "atlas_", "atlas_1001.png")
     * @return Map of sprite keys to their placement data (page, coordinates, dimensions)
     * @throws IOException If texture is too large or file writing fails
     */
    public Map<String, Placement> pack(Path outputDir, String prefix) throws IOException {
        Map<String, Placement> placements = new LinkedHashMap<>();
        List<AtlasPage> pages = new ArrayList<>();

        // Sort: largest dimension first (descending), then alphabetically for determinism
        textures.sort(
            Comparator.<TextureEntry>comparingInt(t -> Math.max(t.image.getWidth(), t.image.getHeight())).reversed()
                .thenComparing(t -> t.spriteKey)
        );

        for (TextureEntry entry : textures) {
            boolean placed = false;
            Rect bestRect = null;
            int bestPageIndex = -1;

            // Try to place in existing pages, find the best fit
            for (int i = 0; i < pages.size(); i++) {
                Rect rect = pages.get(i).insert(entry.image.getWidth(), entry.image.getHeight());
                if (rect != null) {
                    if (bestRect == null || rect.score < bestRect.score) {
                        bestRect = rect;
                        bestPageIndex = i;
                    }
                }
            }

            // If placed in an existing page, finalize placement
            if (bestRect != null) {
                placed = true;
                pages.get(bestPageIndex).placeRect(bestRect, entry.image);
                int udim = 1001 + (bestPageIndex % 10) + (bestPageIndex / 10) * 10;
                placements.put(entry.spriteKey, new Placement(
                    bestPageIndex, udim, bestRect.x, bestRect.y,
                    entry.image.getWidth(), entry.image.getHeight()
                ));
            }

            // Create new page if needed
            if (!placed) {
                AtlasPage newPage = new AtlasPage(atlasSize, powerOfTwo);
                Rect rect = newPage.insert(entry.image.getWidth(), entry.image.getHeight());

                if (rect == null) {
                    throw new IOException("Texture too large for atlas: " + entry.spriteKey +
                        " (" + entry.image.getWidth() + "x" + entry.image.getHeight() + ")");
                }

                pages.add(newPage);
                int pageIdx = pages.size() - 1;
                newPage.placeRect(rect, entry.image);

                int udim = 1001 + (pageIdx % 10) + (pageIdx / 10) * 10;
                placements.put(entry.spriteKey, new Placement(
                    pageIdx, udim, rect.x, rect.y,
                    entry.image.getWidth(), entry.image.getHeight()
                ));
            }
        }

        // Write atlas pages to disk
        IntStream.range(0, pages.size()).parallel().forEach(i -> {
            int udim = 1001 + (i % 10) + (i / 10) * 10;
            String filename = prefix + udim + ".png";
            Path outputPath = outputDir.resolve(filename);
            PngjWriter.write(pages.get(i).image, outputPath);
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[TextureAtlasPacker] Wrote atlas page: " + filename);
            }
        });

        return placements;
    }

    /**
     * Represents the placement of a texture within an atlas page.
     * Contains page index, UDIM coordinate, pixel coordinates, and dimensions.
     */
    public static class Placement {
        private final int page;
        private final int udim;
        private final int x, y, w, h;

        Placement(int page, int udim, int x, int y, int w, int h) {
            this.page = page;
            this.udim = udim;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public int page() { return page; }
        public int udim() { return udim; }
        public int x() { return x; }
        public int y() { return y; }
        public int width() { return w; }
        public int height() { return h; }
    }

    /**
         * Internal structure representing a texture to be packed.
         */
        private record TextureEntry(String spriteKey, BufferedImage image) {

        int getLargestDimension() {
                return Math.max(image.getWidth(), image.getHeight());
            }
        }

    /**
     * Internal structure representing a rectangle (free space or placed texture).
     * The score field is used to evaluate placement quality (lower is better).
     */
    private static class Rect {
        int x, y, width, height;
        int score;

        Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Represents a single atlas page with its image buffer and free space tracking.
     * Uses the MaxRects algorithm to find optimal placement for incoming textures.
     */
    private static class AtlasPage {
        final BufferedImage image;
        final int width, height;
        final List<Rect> freeRects = new ArrayList<>();

        AtlasPage(int size, boolean powerOfTwo) {
            this.width = size;
            this.height = size;
            this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            freeRects.add(new Rect(0, 0, width, height));
        }

        /**
         * Attempts to find the best placement for a texture of the given dimensions.
         * Uses the Best Short Side Fit heuristic: minimize the shorter leftover side.
         *
         * @param w Width of texture to place
         * @param h Height of texture to place
         * @return Rect with placement coordinates and score, or null if doesn't fit
         */
        Rect insert(int w, int h) {
            Rect bestRect = new Rect(0, 0, 0, 0);
            bestRect.score = Integer.MAX_VALUE;
            int bestNodeIndex = -1;

            // Find the free rectangle that gives the best fit
            for (int i = 0; i < freeRects.size(); i++) {
                Rect free = freeRects.get(i);
                if (free.width >= w && free.height >= h) {
                    // Score = minimum of leftover space in each dimension
                    int score = Math.min(free.width - w, free.height - h);
                    if (score < bestRect.score) {
                        bestRect.x = free.x;
                        bestRect.y = free.y;
                        bestRect.width = w;
                        bestRect.height = h;
                        bestRect.score = score;
                        bestNodeIndex = i;
                    }
                }
            }

            if (bestNodeIndex == -1) {
                return null; // Doesn't fit in any free rectangle
            }

            // Split the free rectangle and update free list
            splitFreeNode(freeRects.get(bestNodeIndex), bestRect);
            freeRects.remove(bestNodeIndex);
            pruneFreeList();
            return bestRect;
        }

        /**
         * Places a texture at the given rectangle coordinates.
         *
         * @param rect Rectangle defining where to place the texture
         * @param texture The texture image to draw
         */
        void placeRect(Rect rect, BufferedImage texture) {
            Graphics2D g = image.createGraphics();
            g.drawImage(texture, rect.x, rect.y, null);
            g.dispose();
        }

        /**
         * Splits a free rectangle after placing a texture, creating new free rectangles
         * from the leftover space. This is the core of the MaxRects algorithm.
         *
         * @param freeNode The original free rectangle
         * @param usedNode The rectangle that was just occupied
         */
        private void splitFreeNode(Rect freeNode, Rect usedNode) {
            // Check if the rectangles even overlap
            if (usedNode.x >= freeNode.x + freeNode.width || usedNode.x + usedNode.width <= freeNode.x ||
                usedNode.y >= freeNode.y + freeNode.height || usedNode.y + usedNode.height <= freeNode.y) {
                return;
            }

            // Create new free rectangles from the leftover space

            // Top rectangle (above the placed texture)
            if (usedNode.y > freeNode.y) {
                freeRects.add(new Rect(
                    freeNode.x,
                    freeNode.y,
                    freeNode.width,
                    usedNode.y - freeNode.y));
            }

            // Bottom rectangle (below the placed texture)
            int usedBottom = usedNode.y + usedNode.height;
            int freeBottom = freeNode.y + freeNode.height;
            if (usedBottom < freeBottom) {
                freeRects.add(new Rect(
                    freeNode.x,
                    usedBottom,
                    freeNode.width,
                    freeBottom - usedBottom));
            }

            // Left rectangle (to the left of the placed texture)
            if (usedNode.x > freeNode.x) {
                freeRects.add(new Rect(
                    freeNode.x,
                    usedNode.y,
                    usedNode.x - freeNode.x,
                    usedNode.height));
            }

            // Right rectangle (to the right of the placed texture)
            int usedRight = usedNode.x + usedNode.width;
            int freeRight = freeNode.x + freeNode.width;
            if (usedRight < freeRight) {
                freeRects.add(new Rect(
                    usedRight,
                    usedNode.y,
                    freeRight - usedRight,
                    usedNode.height));
            }
        }

        /**
         * Removes free rectangles that are completely contained within other free rectangles.
         * This optimization reduces the number of rectangles to check during placement.
         */
        private void pruneFreeList() {
            for (int i = 0; i < freeRects.size(); i++) {
                for (int j = i + 1; j < freeRects.size(); j++) {
                    Rect r1 = freeRects.get(i);
                    Rect r2 = freeRects.get(j);
                    if (isContainedIn(r1, r2)) {
                        freeRects.remove(i--);
                        break;
                    }
                    if (isContainedIn(r2, r1)) {
                        freeRects.remove(j--);
                    }
                }
            }
        }

        /**
         * Checks if rectangle 'a' is completely contained within rectangle 'b'.
         */
        private boolean isContainedIn(Rect a, Rect b) {
            return a.x >= b.x && a.y >= b.y &&
                   a.x + a.width <= b.x + b.width &&
                   a.y + a.height <= b.y + b.height;
        }
    }
}
