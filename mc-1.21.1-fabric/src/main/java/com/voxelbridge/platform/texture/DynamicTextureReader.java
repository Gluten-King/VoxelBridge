package com.voxelbridge.platform.texture;

import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;

/**
 * Fabric placeholder for dynamic texture reads.
 * Returns null for now to keep basic export paths functional.
 */
public final class DynamicTextureReader {

    private DynamicTextureReader() {}

    public static BufferedImage tryRead(Identifier location) {
        return null;
    }
}
