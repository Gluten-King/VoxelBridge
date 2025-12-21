package com.voxelbridge.util.debug;

/**
 * Log module enumeration defining the different logging categories in VoxelBridge.
 * Each module routes to its own dedicated log file.
 */
public enum LogModule {
    /**
     * General export operations (StreamingRegionSampler, BlockExporter, etc.)
     */
    EXPORT("export.log"),

    /**
     * Texture loading and atlas packing (TextureAtlasManager, TextureLoader, etc.)
     */
    TEXTURE("texture.log"),

    /**
     * Animation detection and frame extraction (AnimatedTextureHelper)
     */
    ANIMATION("animation.log"),

    /**
     * Performance metrics (duration, memory, stats, sizes)
     */
    PERFORMANCE("performance.log"),

    /**
     * Block entity rendering and textures (BlockEntityRenderer, BannerBlockEntityHandler)
     */
    BLOCKENTITY("blockentity.log"),

    /**
     * glTF-specific export operations (GltfExportService, GltfSceneBuilder)
     */
    GLTF("gltf.log"),

    /**
     * VXB-specific export operations (VxbExportService, VxbSceneBuilder)
     */
    VXB("vxb.log");

    private final String fileName;

    LogModule(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Gets the log file name for this module.
     */
    public String getFileName() {
        return fileName;
    }
}
