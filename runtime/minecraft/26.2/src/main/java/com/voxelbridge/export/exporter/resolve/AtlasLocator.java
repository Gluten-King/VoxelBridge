package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Locates a sprite inside an atlas that contains a given UV coordinate.
 */
public interface AtlasLocator {
    TextureAtlasSprite find(Identifier atlasLocation, float u, float v);
}
