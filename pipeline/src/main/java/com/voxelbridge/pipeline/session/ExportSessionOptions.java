package com.voxelbridge.pipeline.session;

import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;

/** Immutable configuration snapshot for one export session. */
public record ExportSessionOptions(
    AtlasMode atlasMode,
    int atlasSize,
    int atlasPadding,
    int workerThreads,
    ColorMode colorMode,
    CoordinateMode coordinateMode,
    boolean vanillaRandomTransform,
    boolean animation,
    boolean fillCaves,
    boolean decodePbr,
    boolean collapseDoubleSided,
    boolean nonsolidCulling
) {
    public ExportSessionOptions {
        if (atlasMode == null) atlasMode = AtlasMode.ATLAS;
        if (atlasSize < 1) throw new IllegalArgumentException("Atlas size must be positive");
        if (atlasPadding < 0) throw new IllegalArgumentException("Atlas padding must not be negative");
        if (colorMode == null) colorMode = ColorMode.VERTEX_COLOR;
        if (coordinateMode == null) coordinateMode = CoordinateMode.CENTERED;
        workerThreads = Math.max(1, Math.min(128, workerThreads));
    }

    public static ExportSessionOptions defaults() {
        return new ExportSessionOptions(AtlasMode.ATLAS, 8192, 0, 1,
            ColorMode.VERTEX_COLOR, CoordinateMode.CENTERED,
            true, false, false, false, true, true);
    }

    public enum AtlasMode {
        INDIVIDUAL,
        ATLAS
    }
}
