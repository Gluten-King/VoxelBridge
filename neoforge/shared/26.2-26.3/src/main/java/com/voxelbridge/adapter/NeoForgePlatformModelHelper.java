package com.voxelbridge.adapter;

import com.voxelbridge.core.ir.RenderLayer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

/** Preserves NeoForge 26.2 baked colors and normals in the legacy eight-int quad layout. */
public final class NeoForgePlatformModelHelper implements PlatformModelHelper {
    private static final int STRIDE = 8;

    @Override
    public TextureAtlasSprite getQuadSprite(BakedQuad quad) {
        return quad != null ? quad.materialInfo().sprite() : null;
    }

    @Override
    public Direction getQuadDirection(BakedQuad quad) {
        return quad != null ? quad.direction() : null;
    }

    @Override
    public int[] getQuadVertices(BakedQuad quad) {
        if (quad == null) return new int[0];
        int[] vertices = new int[BakedQuad.VERTEX_COUNT * STRIDE];
        Direction direction = quad.direction() != null ? quad.direction() : Direction.UP;
        int faceNormal = BakedNormals.pack(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        for (int index = 0; index < BakedQuad.VERTEX_COUNT; index++) {
            int offset = index * STRIDE;
            var position = quad.position(index);
            long packedUv = quad.packedUV(index);
            int bakedNormal = quad.bakedNormals().normal(index);
            vertices[offset] = Float.floatToRawIntBits(position.x());
            vertices[offset + 1] = Float.floatToRawIntBits(position.y());
            vertices[offset + 2] = Float.floatToRawIntBits(position.z());
            vertices[offset + 3] = quad.bakedColors().color(index);
            vertices[offset + 4] = Float.floatToRawIntBits(UVPair.unpackU(packedUv));
            vertices[offset + 5] = Float.floatToRawIntBits(UVPair.unpackV(packedUv));
            vertices[offset + 6] = 0;
            vertices[offset + 7] = BakedNormals.isUnspecified(bakedNormal) ? faceNormal : bakedNormal;
        }
        return vertices;
    }

    @Override
    public int getQuadTintIndex(BakedQuad quad) {
        return quad != null ? quad.materialInfo().tintIndex() : -1;
    }

    @Override
    public RenderLayer getQuadRenderLayer(BakedQuad quad) {
        if (quad == null || quad.materialInfo().layer() == null) return RenderLayer.UNKNOWN;
        return switch (quad.materialInfo().layer()) {
            case SOLID -> RenderLayer.SOLID;
            case CUTOUT -> RenderLayer.CUTOUT;
            case TRANSLUCENT -> RenderLayer.TRANSLUCENT;
        };
    }
}
