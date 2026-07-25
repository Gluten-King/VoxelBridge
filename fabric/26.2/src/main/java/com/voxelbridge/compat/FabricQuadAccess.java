package com.voxelbridge.compat;

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
            int[] verts = new int[BakedQuad.VERTEX_COUNT * STRIDE];
            for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
                org.joml.Vector3fc pos = quad.position(i);
                int base = i * STRIDE;
                verts[base] = Float.floatToRawIntBits(pos.x());
                verts[base + 1] = Float.floatToRawIntBits(pos.y());
                verts[base + 2] = Float.floatToRawIntBits(pos.z());
                verts[base + 3] = 0xFFFFFFFF; // vertex color not exposed by the record; default opaque white
                long packed = quad.packedUV(i);
                verts[base + 4] = (int) (packed >>> 32); // u bits
                verts[base + 5] = (int) packed;          // v bits
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
}
