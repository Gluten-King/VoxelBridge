package com.voxelbridge.export.texture;

import com.voxelbridge.core.util.color.ColorMode;

/**
 * Core-facing export options for texture/UV processing.
 */
public record ExportOptions(
    AtlasMode atlasMode,
    int atlasSize,
    int atlasPadding,
    ColorMode colorMode,
    boolean animationEnabled,
    boolean pbrDecodeEnabled,
    boolean forceDoubleSided
) {
    public enum AtlasMode {
        INDIVIDUAL,
        ATLAS
    }
}
