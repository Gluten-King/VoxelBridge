package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolved texture reference with optional atlas bounds and sprite info.
 */
public record ResolvedTexture(
    ResourceLocation texture,
    float u0,
    float u1,
    float v0,
    float v1,
    boolean isAtlasTexture,
    TextureAtlasSprite sprite,
    ResourceLocation atlasLocation
) {}
