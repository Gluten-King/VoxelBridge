package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

public class NeoForgePlatformModelHelper implements PlatformModelHelper {
    @Override
    public TextureAtlasSprite getQuadSprite(BakedQuad quad) {
        return quad != null ? quad.sprite() : null;
    }

    @Override
    public Direction getQuadDirection(BakedQuad quad) {
        return quad != null ? quad.direction() : null;
    }

    @Override
    public int[] getQuadVertices(BakedQuad quad) {
        if (quad == null) return new int[0];
        int vertexSize = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        int[] vertices = new int[vertexSize * BakedQuad.VERTEX_COUNT];
        Direction direction = quad.direction() != null ? quad.direction() : Direction.UP;
        int packedNormal = packNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        for (int index = 0; index < BakedQuad.VERTEX_COUNT; index++) {
            int offset = index * vertexSize;
            var position = quad.position(index);
            long packedUv = quad.packedUV(index);
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
    }

    @Override
    public int getQuadTintIndex(BakedQuad quad) {
        return quad != null ? quad.tintIndex() : -1;
    }

    private static int packNormal(float x, float y, float z) {
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }
}
