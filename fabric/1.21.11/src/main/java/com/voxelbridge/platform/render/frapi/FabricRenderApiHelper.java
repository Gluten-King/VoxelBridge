package com.voxelbridge.platform.render.frapi;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.voxelbridge.export.quad.QuadData;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric Rendering API helper for extracting quads from FabricBlockStateModel.
 */
public final class FabricRenderApiHelper {
    private FabricRenderApiHelper() {}

    public static List<BakedQuad> extractQuads(
        FabricBlockStateModel model,
        BlockAndTintGetter level,
        BlockState state,
        BlockPos pos,
        RandomSource rand,
        SpriteFinder spriteFinder
    ) {
        try {
            Renderer renderer = Renderer.get();
            if (renderer == null) return new ArrayList<>();

            List<BakedQuad> fabricQuads = new ArrayList<>();
            MutableMesh mesh = renderer.mutableMesh();
            QuadEmitter emitter = mesh.emitter();

            model.emitQuads(emitter, level, pos, state, rand, face -> false);

            mesh.forEach(q -> fabricQuads.add(toBakedQuad(q, spriteFinder)));

            return fabricQuads;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * Extracts FRAPI quads without routing them through Minecraft's 1.21.11
     * {@link BakedQuad} record. The record no longer stores vertex colors, while
     * Continuity and other FRAPI models may encode their final tint there.
     */
    public static List<QuadData> extractQuadData(
        FabricBlockStateModel model,
        BlockAndTintGetter level,
        BlockState state,
        BlockPos pos,
        RandomSource rand,
        SpriteFinder spriteFinder
    ) {
        try {
            Renderer renderer = Renderer.get();
            if (renderer == null) return new ArrayList<>();

            List<QuadData> result = new ArrayList<>();
            MutableMesh mesh = renderer.mutableMesh();
            QuadEmitter emitter = mesh.emitter();

            model.emitQuads(emitter, level, pos, state, rand, face -> false);
            mesh.forEach(q -> result.add(toQuadData(q, spriteFinder)));
            return result;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static QuadData toQuadData(QuadView quad, SpriteFinder spriteFinder) {
        int vertexSize = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        int[] vertices = new int[vertexSize * BakedQuad.VERTEX_COUNT];
        Direction direction = resolveDirection(quad);
        int packedNormal = packNormal(
            direction.getStepX(), direction.getStepY(), direction.getStepZ());

        for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
            int offset = i * vertexSize;
            vertices[offset] = Float.floatToRawIntBits(quad.x(i));
            vertices[offset + 1] = Float.floatToRawIntBits(quad.y(i));
            vertices[offset + 2] = Float.floatToRawIntBits(quad.z(i));
            vertices[offset + 3] = quad.color(i);
            vertices[offset + 4] = Float.floatToRawIntBits(quad.u(i));
            vertices[offset + 5] = Float.floatToRawIntBits(quad.v(i));
            vertices[offset + 6] = quad.lightmap(i);
            vertices[offset + 7] = packedNormal;
        }

        TextureAtlasSprite sprite = spriteFinder.find(quad, 0);
        return new CapturedFabricQuad(sprite, direction, vertices, quad.tintIndex());
    }

    private static BakedQuad toBakedQuad(QuadView quad, SpriteFinder spriteFinder) {
        Vector3f[] positions = new Vector3f[4];
        long[] packedUvs = new long[4];
        for (int i = 0; i < 4; i++) {
            positions[i] = new Vector3f(quad.x(i), quad.y(i), quad.z(i));
            packedUvs[i] = UVPair.pack(quad.u(i), quad.v(i));
        }

        TextureAtlasSprite sprite = spriteFinder.find(quad, 0);
        Direction direction = resolveDirection(quad);
        int tintIndex = quad.tintIndex();
        boolean shade = true;

        return new BakedQuad(
            positions[0], positions[1], positions[2], positions[3],
            packedUvs[0], packedUvs[1], packedUvs[2], packedUvs[3],
            tintIndex, direction, sprite, shade, 0
        );
    }

    private static Direction resolveDirection(QuadView quad) {
        Direction direction = quad.lightFace();
        if (direction == null) {
            direction = quad.cullFace() != null ? quad.cullFace() : Direction.UP;
        }
        return direction;
    }

    private static int packNormal(float x, float y, float z) {
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }

    private record CapturedFabricQuad(
        TextureAtlasSprite sprite,
        Direction direction,
        int[] vertices,
        int tintIndex
    ) implements QuadData {}
}
