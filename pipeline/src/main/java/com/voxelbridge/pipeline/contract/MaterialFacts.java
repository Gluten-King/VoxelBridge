package com.voxelbridge.pipeline.contract;

/** Runtime-observed material state; export policy is applied later by pipeline. */
public record MaterialFacts(
    BlendMode blendMode,
    boolean emissive,
    boolean doubleSided,
    boolean depthWrite,
    int renderOrder
) {
    public MaterialFacts {
        if (blendMode == null) blendMode = BlendMode.UNKNOWN;
    }

    public static MaterialFacts opaque() {
        return new MaterialFacts(BlendMode.OPAQUE, false, false, true, 0);
    }

    public enum BlendMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        ADDITIVE,
        UNKNOWN
    }
}
