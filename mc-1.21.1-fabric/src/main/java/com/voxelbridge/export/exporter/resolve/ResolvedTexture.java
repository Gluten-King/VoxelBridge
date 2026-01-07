package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;

/**
 * Resolved texture reference with optional atlas bounds and sprite info.
 */
public record ResolvedTexture(
    Identifier texture,
    float u0,
    float u1,
    float v0,
    float v1,
    boolean isAtlasTexture,
    Sprite sprite,
    Identifier atlasLocation
) {}
