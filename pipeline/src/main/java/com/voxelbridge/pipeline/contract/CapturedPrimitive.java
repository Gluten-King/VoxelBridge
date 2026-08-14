package com.voxelbridge.pipeline.contract;

/** Final primitive emitted by an entity, block-entity, glyph, or special renderer. */
public record CapturedPrimitive(
    float[] positions,
    float[] uv0,
    float[] colors,
    float[] normals,
    SpriteRef sprite,
    MaterialFacts material,
    String provenance
) {
    public CapturedPrimitive {
        if (positions == null || positions.length < 9 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("A captured primitive requires at least three XYZ positions");
        }
        int vertexCount = positions.length / 3;
        if (uv0 != null && uv0.length < vertexCount * 2) {
            throw new IllegalArgumentException("UV0 is shorter than the captured vertex count");
        }
        if (material == null) material = MaterialFacts.opaque();
    }

    public int vertexCount() {
        return positions.length / 3;
    }
}
