package com.voxelbridge.config;

import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;

/**
 * Mutable client preference store. Production exporters snapshot these values
 * into {@code ExportSessionOptions} before any work starts.
 */
public final class ExportRuntimeConfig {

    public static final int DEFAULT_SELECTION_LINE_WIDTH = 3;

    private ExportRuntimeConfig() {}

    public enum AtlasMode {
        INDIVIDUAL("Individual textures (one file per sprite)"),
        ATLAS("Packed atlas (UDIM tiles)");

        private final String description;

        AtlasMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum AtlasSize {
        SIZE_128(128, "128x128 (Tiny)"),
        SIZE_256(256, "256x256 (Small)"),
        SIZE_512(512, "512x512 (Medium)"),
        SIZE_1024(1024, "1024x1024 (Normal)"),
        SIZE_2048(2048, "2048x2048 (Large)"),
        SIZE_4096(4096, "4096x4096 (Very Large)"),
        SIZE_8192(8192, "8192x8192 (Maximum)");

        private final int size;
        private final String description;

        AtlasSize(int size, String description) {
            this.size = size;
            this.description = description;
        }

        public int getSize() {
            return size;
        }

        public String getDescription() {
            return description;
        }

        public static AtlasSize fromSize(int size) {
            for (AtlasSize s : values()) {
                if (s.size == size) return s;
            }
            return SIZE_8192; // default
        }
    }

    private static AtlasMode atlasMode = AtlasMode.ATLAS;
    private static AtlasSize atlasSize = AtlasSize.SIZE_8192;
    private static int atlasPadding = 0;
    private static ColorMode colorMode = ColorMode.BOTH;
    private static CoordinateMode coordinateMode = CoordinateMode.CENTERED;
    private static int exportThreadCount = 16;
    // Enable vanilla random transforms (e.g., grass offset, random model rotations).
    private static boolean vanillaRandomTransformEnabled = true;
    // Export animated textures (mcmeta-driven).
    private static boolean animationEnabled = false;
    // FILLCAVE: treat dark cave_air (skylight = 0) as solid for occlusion culling.
    private static boolean fillCaveEnabled = false;
    // Export decoded LabPBR channel maps from _n/_s.
    private static boolean pbrDecodeEnabled = false;
    // Global log output toggle.
    private static boolean loggingEnabled = false;
    // Export double-sided by collapsing back-to-back faces for certain blocks.
    private static boolean exportDoubleSidedEnabled = true;
    // Cull nonsolid faces fully covered by solid neighbors.
    private static boolean nonsolidCullingEnabled = true;
    // Width of the in-world selection and export-progress outlines, in pixels.
    private static int selectionLineWidth = DEFAULT_SELECTION_LINE_WIDTH;
    private static Runnable changeListener;

    private static void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    public static AtlasMode getAtlasMode() {
        return atlasMode;
    }

    public static void setAtlasMode(AtlasMode mode) {
        if (mode != null && mode != atlasMode) {
            atlasMode = mode;
            notifyChanged();
        }
    }

    public static AtlasSize getAtlasSize() {
        return atlasSize;
    }

    public static void setAtlasSize(AtlasSize size) {
        if (size != null && size != atlasSize) {
            atlasSize = size;
            notifyChanged();
        }
    }

    public static int getAtlasPadding() {
        return atlasPadding;
    }

    public static boolean setAtlasPadding(int padding) {
        if (padding == 0 || padding == 4 || padding == 8 || padding == 12 || padding == 16) {
            if (atlasPadding != padding) {
                atlasPadding = padding;
                notifyChanged();
            }
            return true;
        }
        return false;
    }

    public static CoordinateMode getCoordinateMode() {
        return coordinateMode;
    }

    public static void setCoordinateMode(CoordinateMode mode) {
        if (mode != null && mode != coordinateMode) {
            coordinateMode = mode;
            notifyChanged();
        }
    }

    public static int getExportThreadCount() {
        return exportThreadCount;
    }

    public static void setExportThreadCount(int count) {
        int next;
        if (count < 1) {
            next = 1;
        } else if (count > 128) {
            next = 128;
        } else {
            next = count;
        }
        if (exportThreadCount != next) {
            exportThreadCount = next;
            notifyChanged();
        }
    }

    public static boolean isVanillaRandomTransformEnabled() {
        return vanillaRandomTransformEnabled;
    }

    public static void setVanillaRandomTransformEnabled(boolean enabled) {
        if (vanillaRandomTransformEnabled != enabled) {
            vanillaRandomTransformEnabled = enabled;
            notifyChanged();
        }
    }

    public static ColorMode getColorMode() {
        return colorMode;
    }

    public static void setColorMode(ColorMode mode) {
        if (mode != null && mode != colorMode) {
            colorMode = mode;
            notifyChanged();
        }
    }

    public static boolean isAnimationEnabled() {
        return animationEnabled;
    }

    public static void setAnimationEnabled(boolean enabled) {
        if (animationEnabled != enabled) {
            animationEnabled = enabled;
            notifyChanged();
        }
    }

    public static boolean isFillCaveEnabled() {
        return fillCaveEnabled;
    }

    public static void setFillCaveEnabled(boolean enabled) {
        if (fillCaveEnabled != enabled) {
            fillCaveEnabled = enabled;
            notifyChanged();
        }
    }

    public static boolean isPbrDecodeEnabled() {
        return pbrDecodeEnabled;
    }

    public static void setPbrDecodeEnabled(boolean enabled) {
        if (pbrDecodeEnabled != enabled) {
            pbrDecodeEnabled = enabled;
            notifyChanged();
        }
    }

    public static boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public static void setLoggingEnabled(boolean enabled) {
        if (loggingEnabled != enabled) {
            loggingEnabled = enabled;
            notifyChanged();
        }
    }

    public static boolean isExportDoubleSidedEnabled() {
        return exportDoubleSidedEnabled;
    }

    public static void setExportDoubleSidedEnabled(boolean enabled) {
        if (exportDoubleSidedEnabled != enabled) {
            exportDoubleSidedEnabled = enabled;
            notifyChanged();
        }
    }

    public static boolean isNonsolidCullingEnabled() {
        return nonsolidCullingEnabled;
    }

    public static void setNonsolidCullingEnabled(boolean enabled) {
        if (nonsolidCullingEnabled != enabled) {
            nonsolidCullingEnabled = enabled;
            notifyChanged();
        }
    }

    public static int getSelectionLineWidth() {
        return selectionLineWidth;
    }

    public static void setSelectionLineWidth(int width) {
        int next = Math.max(1, Math.min(8, width));
        if (selectionLineWidth != next) {
            selectionLineWidth = next;
            notifyChanged();
        }
    }

    public static void resetDefaults() {
        atlasMode = AtlasMode.ATLAS;
        atlasSize = AtlasSize.SIZE_8192;
        atlasPadding = 0;
        colorMode = ColorMode.BOTH;
        coordinateMode = CoordinateMode.CENTERED;
        exportThreadCount = 16;
        vanillaRandomTransformEnabled = true;
        animationEnabled = false;
        fillCaveEnabled = false;
        pbrDecodeEnabled = false;
        loggingEnabled = false;
        exportDoubleSidedEnabled = true;
        nonsolidCullingEnabled = true;
        selectionLineWidth = DEFAULT_SELECTION_LINE_WIDTH;
        notifyChanged();
    }

}

