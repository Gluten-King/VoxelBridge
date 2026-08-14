package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Locates a sprite inside an atlas that contains a given UV coordinate.
 */
public interface AtlasLocator {
    TextureAtlasSprite find(ResourceLocation atlasLocation, float u, float v);
}
