package com.voxelbridge.export.scene.gltf;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packs block entity textures into atlas pages using the MaxRects algorithm.
 * This implementation is based on the algorithm by Jukka Jylänki.
 */
public final class BlockEntityAtlasPacker {
    private final int atlasSize;
    private final List<TextureEntry> textures = new ArrayList<>();
    private final boolean powerOfTwo;

    public BlockEntityAtlasPacker(int atlasSize, boolean powerOfTwo) {
        this.atlasSize = atlasSize;
        this.powerOfTwo = powerOfTwo;
    }

    public void addTexture(String spriteKey, BufferedImage image) {
        textures.add(new TextureEntry(spriteKey, image));
    }

    public Map<String, Placement> pack(Path outputDir, String prefix) throws IOException {
        Map<String, Placement> placements = new LinkedHashMap<>();
        List<AtlasPage> pages = new ArrayList<>();

        // Sort：先按最大边长降序，再按资源名字母/数字升序保证确定性
        textures.sort(
            Comparator.<TextureEntry>comparingInt(t -> Math.max(t.image.getWidth(), t.image.getHeight())).reversed()
                .thenComparing(t -> t.spriteKey)
        );

        for (TextureEntry entry : textures) {
            boolean placed = false;
            Rect bestRect = null;
            int bestPageIndex = -1;

            // Try to place in existing pages
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
        for (int i = 0; i < pages.size(); i++) {
            int udim = 1001 + (i % 10) + (i / 10) * 10;
            String filename = prefix + udim + ".png";
            Path outputPath = outputDir.resolve(filename);
            ImageIO.write(pages.get(i).image, "png", outputPath.toFile());
            System.out.println("[BlockEntityAtlasPacker] Wrote atlas page: " + filename);
        }

        return placements;
    }

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

    private static class TextureEntry {
        final String spriteKey;
        final BufferedImage image;

        TextureEntry(String spriteKey, BufferedImage image) {
            this.spriteKey = spriteKey;
            this.image = image;
        }

        int getLargestDimension() {
            return Math.max(image.getWidth(), image.getHeight());
        }
    }

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

        Rect insert(int w, int h) {
            Rect bestRect = new Rect(0, 0, 0, 0);
            bestRect.score = Integer.MAX_VALUE;
            int bestNodeIndex = -1;

            for (int i = 0; i < freeRects.size(); i++) {
                Rect free = freeRects.get(i);
                if (free.width >= w && free.height >= h) {
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
                return null;
            }

            splitFreeNode(freeRects.get(bestNodeIndex), bestRect);
            freeRects.remove(bestNodeIndex);
            pruneFreeList();
            return bestRect;
        }

        void placeRect(Rect rect, BufferedImage texture) {
            Graphics2D g = image.createGraphics();
            g.drawImage(texture, rect.x, rect.y, null);
            g.dispose();
        }

        private void splitFreeNode(Rect freeNode, Rect usedNode) {
            if (usedNode.x >= freeNode.x + freeNode.width || usedNode.x + usedNode.width <= freeNode.x ||
                usedNode.y >= freeNode.y + freeNode.height || usedNode.y + usedNode.height <= freeNode.y) {
                return;
            }

            
            if (usedNode.y > freeNode.y) {
                freeRects.add(new Rect(
                    freeNode.x,
                    freeNode.y,
                    freeNode.width,
                    usedNode.y - freeNode.y));
            }
            
            int usedBottom = usedNode.y + usedNode.height;
            int freeBottom = freeNode.y + freeNode.height;
            if (usedBottom < freeBottom) {
                freeRects.add(new Rect(
                    freeNode.x,
                    usedBottom,
                    freeNode.width,
                    freeBottom - usedBottom));
            }
            
            if (usedNode.x > freeNode.x) {
                freeRects.add(new Rect(
                    freeNode.x,
                    usedNode.y,
                    usedNode.x - freeNode.x,
                    usedNode.height));
            }
            
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

        private boolean isContainedIn(Rect a, Rect b) {
            return a.x >= b.x && a.y >= b.y &&
                   a.x + a.width <= b.x + b.width &&
                   a.y + a.height <= b.y + b.height;
        }
    }
}
