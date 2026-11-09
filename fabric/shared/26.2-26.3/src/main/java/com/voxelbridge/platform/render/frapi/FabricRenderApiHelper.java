package com.voxelbridge.platform.render.frapi;

import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric Rendering API helper for extracting quads from FabricBlockStateModel.
 */
public final class FabricRenderApiHelper {
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_ERROR =
        new java.util.concurrent.atomic.AtomicBoolean();
    private FabricRenderApiHelper() {}

    public static List<BakedQuad> extractQuads(
        FabricBlockStateModel model,
        BlockAndLightGetter level,
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

            if (!(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter tintGetter)) {
                return fabricQuads;
            }
            model.emitQuads(emitter, tintGetter, pos, state, rand, face -> false);

            if (spriteFinder != null && mesh != null) {
                mesh.forEach(q -> {
                    TextureAtlasSprite sprite = spriteFinder.find(q);
                    if (sprite != null) {
                        fabricQuads.add(q.toBakedQuad(sprite));
                    }
                });
            }

            return fabricQuads;
        } catch (Throwable t) {
            if (LOGGED_ERROR.compareAndSet(false, true)) {
                VoxelBridgeLogger.warn(LogModule.EXPORT,
                    "FRAPI quad extraction failed; using vanilla model parts: " + t);
            }
            return new ArrayList<>();
        }
    }

    /**
     * Extracts FRAPI geometry without converting through 26.2's BakedQuad.
     * That conversion preserves the tint index but drops per-vertex colors,
     * which are where Continuity stores the resolved color of overlay quads.
     */
    public static List<QuadData> extractQuadData(
        FabricBlockStateModel model,
        BlockAndLightGetter level,
        BlockState state,
        BlockPos pos,
        RandomSource rand,
        SpriteFinder spriteFinder
    ) {
        try {
            Renderer renderer = Renderer.get();
            if (renderer == null || spriteFinder == null
                    || !(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter tintGetter)) {
                return new ArrayList<>();
            }

            List<QuadData> result = new ArrayList<>();
            MutableMesh mesh = renderer.mutableMesh();
            QuadEmitter emitter = mesh.emitter();
            model.emitQuads(emitter, tintGetter, pos, state, rand, face -> false);
            mesh.forEach(quad -> {
                TextureAtlasSprite sprite = spriteFinder.find(quad);
                if (sprite != null) {
                    result.add(toQuadData(quad, sprite));
                }
            });
            return result;
        } catch (Throwable t) {
            if (LOGGED_ERROR.compareAndSet(false, true)) {
                VoxelBridgeLogger.warn(LogModule.EXPORT,
                    "FRAPI quad extraction failed; using vanilla model parts: " + t);
            }
            return new ArrayList<>();
        }
    }

    private static QuadData toQuadData(QuadView quad, TextureAtlasSprite sprite) {
        // QuadData intentionally uses the legacy 8-int BLOCK layout consumed by
        // VertexExtractor. MC 26.2's live DefaultVertexFormat.BLOCK is 7 ints.
        int vertexSize = 8;
        int[] vertices = new int[vertexSize * 4];
        Direction direction = resolveDirection(quad);
        int packedNormal = packNormal(
            direction.getStepX(), direction.getStepY(), direction.getStepZ());

        for (int i = 0; i < 4; i++) {
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

        return new CapturedFabricQuad(
            sprite, direction, quad.cullFace(), vertices, quad.tintIndex());
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
        Direction cullDirection,
        int[] vertices,
        int tintIndex
    ) implements QuadData {}

}
