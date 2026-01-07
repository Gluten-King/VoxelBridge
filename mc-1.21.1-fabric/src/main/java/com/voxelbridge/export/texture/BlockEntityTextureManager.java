package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;

/**
 * Minimal Fabric stub for block entity texture registration.
 * Basic quad export does not support BE textures yet.
 */
public final class BlockEntityTextureManager {
    private BlockEntityTextureManager() {}

    public static void clear(ExportContext ctx) {
        // no-op for basic quad export
    }

    public static String getRegisteredLocation(ExportContext ctx, String spriteKey) {
        return null;
    }

    public static String getTextureFilename(String spriteKey) {
        return null;
    }
}
