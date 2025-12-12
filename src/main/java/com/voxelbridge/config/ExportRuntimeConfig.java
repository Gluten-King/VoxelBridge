package com.voxelbridge.config;

import com.voxelbridge.export.CoordinateMode;

/**
 * Runtime export configuration (client-side toggles controlled via /voxelbridge).
 */
public final class ExportRuntimeConfig {

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

    public enum ColorMode {
        COLORMAP("ColorMap (TEXCOORD_1 + texture)"),
        VERTEX_COLOR("Vertex Color (COLOR_0 attribute)");

        private final String description;

        ColorMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private static AtlasMode atlasMode = AtlasMode.ATLAS;
    private static AtlasSize atlasSize = AtlasSize.SIZE_8192;
    private static ColorMode colorMode = ColorMode.VERTEX_COLOR;
    private static CoordinateMode coordinateMode = CoordinateMode.CENTERED;
    private static int exportThreadCount = Runtime.getRuntime().availableProcessors();
    // 控制是否应用原版基于位置哈希的随机变换（草丛偏移、随机模型旋转等）
    private static boolean vanillaRandomTransformEnabled = true;
    // 控制是否导出动画贴图序列（默认为关闭）
    private static boolean animationEnabled = false;

    public static AtlasMode getAtlasMode() {
        return atlasMode;
    }

    public static void setAtlasMode(AtlasMode mode) {
        if (mode != null) {
            atlasMode = mode;
        }
    }

    public static AtlasSize getAtlasSize() {
        return atlasSize;
    }

    public static void setAtlasSize(AtlasSize size) {
        if (size != null) {
            atlasSize = size;
        }
    }

    public static CoordinateMode getCoordinateMode() {
        return coordinateMode;
    }

    public static void setCoordinateMode(CoordinateMode mode) {
        if (mode != null) {
            coordinateMode = mode;
        }
    }

    public static int getExportThreadCount() {
        return exportThreadCount;
    }

    public static void setExportThreadCount(int count) {
        if (count < 1) {
            exportThreadCount = 1;
        } else if (count > 32) {
            exportThreadCount = 32;
        } else {
            exportThreadCount = count;
        }
    }

    public static boolean isVanillaRandomTransformEnabled() {
        return vanillaRandomTransformEnabled;
    }

    public static void setVanillaRandomTransformEnabled(boolean enabled) {
        vanillaRandomTransformEnabled = enabled;
    }

    public static ColorMode getColorMode() {
        return colorMode;
    }

    public static void setColorMode(ColorMode mode) {
        if (mode != null) {
            colorMode = mode;
        }
    }

    public static boolean isAnimationEnabled() {
        return animationEnabled;
    }

    public static void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
    }

}
