package com.voxelbridge.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.File;

/**
 * Fabric-specific texture access using Mixin accessors.
 */
public final class FabricTextureAccess {

    private FabricTextureAccess() {
    }

    /**
     * Gets the file from an HttpTexture.
     */
    public static File getHttpTextureFile(AbstractTexture texture) {
        // HttpTexture is removed in 1.21.4, no public replacement.
        return null;
    }

    /**
     * Checks if a texture is an HttpTexture.
     */
    public static boolean isHttpTexture(AbstractTexture texture) {
        return false;
    }

    /**
     * Gets pixels from a DynamicTexture.
     */
    public static NativeImage getDynamicTexturePixels(AbstractTexture texture) {
        if (texture instanceof DynamicTexture) {
            try {
                return ((DynamicTexture) texture).getPixels();
            } catch (Throwable t) {
                // Fallback to public method
            }
        }
        return null;
    }
}
