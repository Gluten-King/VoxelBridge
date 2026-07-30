package com.voxelbridge.compat;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.core.Direction;

/**
 * Fabric-specific BakedQuad access using public API.
 */
public final class FabricQuadAccess {

    private FabricQuadAccess() {
    }

    public static TextureAtlasSprite getSprite(BakedQuad quad) {
        if (quad == null)
            return null;
        try {
            return quad.sprite();
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
            int vertexSize = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
            int[] vertices = new int[vertexSize * BakedQuad.VERTEX_COUNT];
            Direction direction = quad.direction() != null ? quad.direction() : Direction.UP;
            int packedNormal = packNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
                int offset = i * vertexSize;
                var position = quad.position(i);
                long packedUv = quad.packedUV(i);
                vertices[offset] = Float.floatToRawIntBits(position.x());
                vertices[offset + 1] = Float.floatToRawIntBits(position.y());
                vertices[offset + 2] = Float.floatToRawIntBits(position.z());
                vertices[offset + 3] = 0xFFFFFFFF;
                vertices[offset + 4] = Float.floatToRawIntBits(UVPair.unpackU(packedUv));
                vertices[offset + 5] = Float.floatToRawIntBits(UVPair.unpackV(packedUv));
                vertices[offset + 6] = 0;
                vertices[offset + 7] = packedNormal;
            }
            return vertices;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    public static int getTintIndex(BakedQuad quad) {
        if (quad == null)
            return -1;
        try {
            return quad.tintIndex();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int packNormal(float x, float y, float z) {
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }
}
