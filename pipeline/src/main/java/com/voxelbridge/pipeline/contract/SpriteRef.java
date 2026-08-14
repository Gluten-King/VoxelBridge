package com.voxelbridge.pipeline.contract;

/** Resolved sprite identity and atlas bounds. A null atlas denotes a standalone texture. */
public record SpriteRef(
    ResourceId id,
    ResourceId atlas,
    float u0,
    float u1,
    float v0,
    float v1,
    int pixelWidth,
    int pixelHeight,
    boolean runtimeDynamic
) {
    public SpriteRef {
        if (id == null) throw new IllegalArgumentException("Sprite id is required");
        if (!Float.isFinite(u0) || !Float.isFinite(u1)
            || !Float.isFinite(v0) || !Float.isFinite(v1)) {
            throw new IllegalArgumentException("Sprite bounds must be finite");
        }
        if (pixelWidth < 0 || pixelHeight < 0) {
            throw new IllegalArgumentException("Sprite dimensions must be non-negative");
        }
    }

    public static SpriteRef standalone(ResourceId id, int width, int height, boolean runtimeDynamic) {
        return new SpriteRef(id, null, 0f, 1f, 0f, 1f, width, height, runtimeDynamic);
    }

    public boolean atlased() {
        return atlas != null;
    }
}
