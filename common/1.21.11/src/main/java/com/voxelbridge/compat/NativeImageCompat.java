package com.voxelbridge.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.adapter.Adapters;

/**
 * Version-agnostic helpers for NativeImage API drift.
 * Delegates to PlatformRenderHelper.
 */
public final class NativeImageCompat {

    private NativeImageCompat() {}

    public static int getPixelRgba(NativeImage img, int x, int y) {
        return Adapters.getTextureHelper().getPixelRgba(img, x, y);
    }
}
