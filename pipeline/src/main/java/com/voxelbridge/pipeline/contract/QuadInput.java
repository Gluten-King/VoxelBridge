package com.voxelbridge.pipeline.contract;

/**
 * Reusable zero-copy quad view. Producers may reuse the instance and backing
 * arrays after QuadSink.accept returns; consumers must copy only when buffering.
 */
public final class QuadInput {
    private float[] positions;
    private float[] uv0;
    private float[] uv1;
    private float[] normal;
    private float[] colors;
    private SpriteRef sprite;
    private MaterialFacts material;
    private OcclusionFacts occlusion;
    private Face face = Face.NONE;
    private Face cullFace = Face.NONE;
    private String provenance;

    public QuadInput set(
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] normal,
        float[] colors,
        SpriteRef sprite,
        MaterialFacts material,
        OcclusionFacts occlusion,
        Face face,
        Face cullFace,
        String provenance
    ) {
        if (positions == null || positions.length < 12) {
            throw new IllegalArgumentException("A quad requires four XYZ positions");
        }
        if (uv0 != null && uv0.length < 8) {
            throw new IllegalArgumentException("UV0 must be absent or contain four UV pairs");
        }
        if (normal != null && normal.length < 3) {
            throw new IllegalArgumentException("Normal must be absent or contain XYZ");
        }
        this.positions = positions;
        this.uv0 = uv0;
        this.uv1 = uv1;
        this.normal = normal;
        this.colors = colors;
        this.sprite = sprite;
        this.material = material == null ? MaterialFacts.opaque() : material;
        this.occlusion = occlusion == null ? OcclusionFacts.unknown() : occlusion;
        this.face = face == null ? Face.NONE : face;
        this.cullFace = cullFace == null ? Face.NONE : cullFace;
        this.provenance = provenance;
        return this;
    }

    public float[] positions() { return positions; }
    public float[] uv0() { return uv0; }
    public float[] uv1() { return uv1; }
    public float[] normal() { return normal; }
    public float[] colors() { return colors; }
    public SpriteRef sprite() { return sprite; }
    public MaterialFacts material() { return material; }
    public OcclusionFacts occlusion() { return occlusion; }
    public Face face() { return face; }
    public Face cullFace() { return cullFace; }
    public String provenance() { return provenance; }
}
