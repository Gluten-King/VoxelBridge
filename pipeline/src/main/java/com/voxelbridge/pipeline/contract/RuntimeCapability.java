package com.voxelbridge.pipeline.contract;

/** Explicit runtime features used instead of Minecraft version checks. */
public enum RuntimeCapability {
    BLOCK_MODEL_QUADS,
    VANILLA_FACE_VISIBILITY,
    FACE_OCCLUSION_SHAPE,
    ENTITY_RENDER_CAPTURE,
    BLOCK_ENTITY_RENDER_CAPTURE,
    GLYPH_TEXTURE_READBACK,
    DYNAMIC_TEXTURE_READBACK,
    ATLAS_REGION_RESOLUTION,
    FINAL_MATERIAL_STATE,
    MOD_MODEL_GEOMETRY
}
