package com.voxelbridge.util.debug;

/**
 * Log module enumeration defining the logging categories in VoxelBridge.
 * Each module maps to a log4j2 logger name and a dedicated log file.
 */
public enum LogModule {
    EXPORT("export", "export.log"),

    SAMPLER_BLOCK("sampler.block", "sampler_block.log"),
    SAMPLER_BLOCKENTITY("sampler.blockentity", "sampler_blockentity.log"),
    SAMPLER_ENTITY("sampler.entity", "sampler_entity.log"),
    SAMPLER_FLUID("sampler.fluid", "sampler_fluid.log"),

    TEXTURE("texture", "texture.log"),
    TEXTURE_RESOLVE("texture.resolve", "texture_resolve.log"),
    TEXTURE_REGISTER("texture.register", "texture_register.log"),
    TEXTURE_ATLAS("texture.atlas", "texture_atlas.log"),

    ANIMATION("animation", "animation.log"),
    UV_REMAP("uv.remap", "uv_remap.log"),

    PERFORMANCE("performance", "performance.log"),
    BLOCKENTITY("blockentity", "blockentity.log"),
    ENTITY("entity", "entity.log"),
    GLTF("gltf", "gltf.log"),
    LOD("lod", "lod.log"),
    LOD_BAKE("lod.bake", "bake.log");

    private final String loggerName;
    private final String fileName;

    LogModule(String loggerName, String fileName) {
        this.loggerName = loggerName;
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public String getLoggerName() {
        return loggerName;
    }
}
