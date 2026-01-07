package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;

/**
 * Locates a sprite inside an atlas that contains a given UV coordinate.
 */
public interface AtlasLocator {
    Sprite find(Identifier atlasLocation, float u, float v);
}
