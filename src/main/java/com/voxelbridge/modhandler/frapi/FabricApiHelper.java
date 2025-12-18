package com.voxelbridge.modhandler.frapi;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Fabric Rendering API helper for extracting quads from FabricBakedModel.
 * Handles CTM models that require Fabric API for proper quad emission.
 */
public final class FabricApiHelper {

    private FabricApiHelper() {}

    /**
     * Extracts quads from a FabricBakedModel using Fabric Rendering API.
     * This is required for CTM/connected textures support.
     *
     * @param model the fabric baked model
     * @param level world level
     * @param state block state
     * @param pos block position
     * @param rand random source
     * @param spriteFinder sprite finder for texture resolution
     * @return list of baked quads, or empty list if extraction fails
     */
    public static List<BakedQuad> extractQuads(
        FabricBakedModel model,
        Level level,
        BlockState state,
        BlockPos pos,
        RandomSource rand,
        SpriteFinder spriteFinder
    ) {
        try {
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer == null) return new ArrayList<>();

            List<BakedQuad> fabricQuads = new ArrayList<>();

            // Init base layer
            final LinkedList<MeshBuilder> builders = new LinkedList<>();
            final LinkedList<QuadEmitter> emitters = new LinkedList<>();
            final LinkedList<RenderContext.QuadTransform> transforms = new LinkedList<>();

            MeshBuilder baseBuilder = renderer.meshBuilder();
            builders.push(baseBuilder);
            emitters.push(baseBuilder.getEmitter());
            transforms.push(null);

            RenderContext context = new RenderContext() {
                @Override
                public QuadEmitter getEmitter() {
                    return emitters.peek();
                }

                @Override
                public boolean isFaceCulled(Direction face) {
                    return false;
                }

                @Override
                public void pushTransform(RenderContext.QuadTransform transform) {
                    MeshBuilder layerBuilder = renderer.meshBuilder();
                    builders.push(layerBuilder);
                    emitters.push(layerBuilder.getEmitter());
                    transforms.push(transform);
                }

                @Override
                public void popTransform() {
                    if (emitters.size() <= 1) {
                        return; // Base layer, cannot pop
                    }

                    MeshBuilder topBuilder = builders.pop();
                    emitters.pop();
                    RenderContext.QuadTransform transform = transforms.pop();
                    QuadEmitter target = emitters.peek();

                    Mesh mesh = topBuilder.build();
                    mesh.forEach(q -> {
                        target.copyFrom(q);
                        if (transform.transform(target)) {
                            target.emit();
                        }
                    });
                }

                @Override
                public RenderContext.BakedModelConsumer bakedModelConsumer() {
                    RenderContext current = this;
                    return new RenderContext.BakedModelConsumer() {
                        @Override
                        public void accept(BakedModel bakedModel) {
                            if (bakedModel instanceof FabricBakedModel fbm) {
                                fbm.emitBlockQuads(level, state, pos, () -> rand, current);
                            }
                        }

                        @Override
                        public void accept(BakedModel bakedModel, BlockState modelState) {
                            if (bakedModel instanceof FabricBakedModel fbm) {
                                BlockState targetState = (modelState != null) ? modelState : state;
                                fbm.emitBlockQuads(level, targetState, pos, () -> rand, current);
                            }
                        }
                    };
                }
            };

            // Emit quads through Fabric API
            model.emitBlockQuads(level, state, pos, () -> rand, context);

            // Convert mesh to BakedQuads
            Mesh mesh = baseBuilder.build();
            mesh.forEach(q -> fabricQuads.add(toBakedQuad(q, spriteFinder)));

            return fabricQuads;
        } catch (Throwable t) {
            // Fabric API extraction failed, fall back to vanilla
            return new ArrayList<>();
        }
    }

    /**
     * Converts a Fabric QuadView to a BakedQuad.
     */
    private static BakedQuad toBakedQuad(QuadView quad, SpriteFinder spriteFinder) {
        int vertexSize = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
        int[] vertices = new int[vertexSize * 4];

        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;
            vertices[offset + 0] = Float.floatToRawIntBits(quad.x(i));
            vertices[offset + 1] = Float.floatToRawIntBits(quad.y(i));
            vertices[offset + 2] = Float.floatToRawIntBits(quad.z(i));
            vertices[offset + 3] = quad.color(i);
            vertices[offset + 4] = Float.floatToRawIntBits(quad.u(i));
            vertices[offset + 5] = Float.floatToRawIntBits(quad.v(i));
            vertices[offset + 6] = quad.lightmap(i);

            if (quad.hasNormal(i)) {
                float nx = quad.normalX(i);
                float ny = quad.normalY(i);
                float nz = quad.normalZ(i);
                vertices[offset + 7] = packNormal(nx, ny, nz);
            } else {
                Direction dir = quad.lightFace();
                if (dir != null) {
                    vertices[offset + 7] = packNormal(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                } else {
                    vertices[offset + 7] = packNormal(0, 1, 0);
                }
            }
        }

        TextureAtlasSprite sprite = spriteFinder.find(quad, 0);
        Direction cullFace = quad.cullFace();
        int tintIndex = quad.colorIndex();
        boolean shade = true;

        return new BakedQuad(vertices, tintIndex, cullFace, sprite, shade);
    }

    private static int packNormal(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 0.0001f) {
            x /= len;
            y /= len;
            z /= len;
        }
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }
}
