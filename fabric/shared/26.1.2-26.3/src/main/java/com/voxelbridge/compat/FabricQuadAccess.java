package com.voxelbridge.compat;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * Fabric-specific BakedQuad access using public API.
 * MC 26.1 BakedQuad is a record exposing position(i)/packedUV(i)/materialInfo().
 */
public final class FabricQuadAccess {

    private static final int STRIDE = 8;

    private FabricQuadAccess() {
    }

    public static TextureAtlasSprite getSprite(BakedQuad quad) {
        if (quad == null)
            return null;
        try {
            return quad.materialInfo().sprite();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Direction getDirection(BakedQuad quad) {
        if (quad == null)
            return null;
        try {
            return quad.direction();
        } catch (Throwable t) {
            return null;
        }
    }

    public static int[] getVertices(BakedQuad quad) {
        if (quad == null)
            return new int[0];
        try {
            // FaceBakery packs atlas-space UVs already:
            //   UVPair.pack(sprite.getU(local), sprite.getV(local))
            // Do NOT expand again — VertexExtractor / OverlayManager un-expand with
            // sprite.getU0/U1. Double getU/getV collapsed every block UV to one texel
            // (solid-color blocks). Entity ModelPart path is different (local 0..1 + wrap).
            int[] verts = new int[BakedQuad.VERTEX_COUNT * STRIDE];
            for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
                org.joml.Vector3fc pos = quad.position(i);
                int base = i * STRIDE;
                verts[base] = Float.floatToRawIntBits(pos.x());
                verts[base + 1] = Float.floatToRawIntBits(pos.y());
                verts[base + 2] = Float.floatToRawIntBits(pos.z());
                verts[base + 3] = 0xFFFFFFFF; // vertex color not exposed by the record; default opaque white
                long packed = quad.packedUV(i);
                verts[base + 4] = Float.floatToRawIntBits(UVPair.unpackU(packed));
                verts[base + 5] = Float.floatToRawIntBits(UVPair.unpackV(packed));
            }
            return verts;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    public static int getTintIndex(BakedQuad quad) {
        if (quad == null)
            return -1;
        try {
            return quad.materialInfo().tintIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Maps the baked quad's ChunkSectionLayer to VoxelBridge RenderLayer for glTF alphaMode.
     */
    public static com.voxelbridge.core.ir.RenderLayer getRenderLayer(BakedQuad quad) {
        if (quad == null) {
            return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
        }
        try {
            var layer = quad.materialInfo().layer();
            if (layer == null) {
                return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
            }
            return switch (layer) {
                case SOLID -> com.voxelbridge.core.ir.RenderLayer.SOLID;
                case CUTOUT -> com.voxelbridge.core.ir.RenderLayer.CUTOUT;
                case TRANSLUCENT -> com.voxelbridge.core.ir.RenderLayer.TRANSLUCENT;
            };
        } catch (Throwable t) {
            return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
        }
    }
}
