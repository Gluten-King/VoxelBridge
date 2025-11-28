package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.BlockEntityDebugLogger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders BlockEntities and captures their geometry to a SceneSink.
 * This replaces the old BerRenderHelper + BerCaptureBuffer system.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityRenderer {

    private static final float[] EMPTY_UV = new float[8];
    private static final float[] NORMAL_UP = new float[] {
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f
    };
    private static final BlockEntityAtlasLocator ATLAS_LOCATOR = new BlockEntityAtlasLocator(Minecraft.getInstance());
    private static final ThreadLocal<TextureOverrideMap> OVERRIDES = new ThreadLocal<>();

    private BlockEntityRenderer() {}

    /**
     * Renders a BlockEntity and outputs geometry to the scene sink.
     *
     * @param ctx Export context
     * @param blockEntity The block entity to render
     * @param sceneSink Output sink for geometry
     * @param offsetX X offset for positioning
     * @param offsetY Y offset for positioning
     * @param offsetZ Z offset for positioning
     * @return true if the BlockEntity was successfully rendered
     */
    public static boolean render(
        ExportContext ctx,
        BlockEntity blockEntity,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        return render(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, null);
    }

    public static boolean render(
        ExportContext ctx,
        BlockEntity blockEntity,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides
    ) {
        com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] Attempting to render BlockEntity: " + blockEntity.getClass().getSimpleName() + " at " + blockEntity.getBlockPos());

        BlockEntityRenderDispatcher dispatcher = ctx.getMc().getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer =
            (net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity>)
            dispatcher.getRenderer(blockEntity);

        if (renderer == null) {
            com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] No renderer found for: " + blockEntity.getClass().getSimpleName());
            return false;
        }

        com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] Found renderer: " + renderer.getClass().getSimpleName());

        AtomicBoolean success = new AtomicBoolean(false);

        // Execute rendering on main thread
        Minecraft.getInstance().executeBlocking(() -> {
            try {
                if (overrides != null) {
                    OVERRIDES.set(overrides);
                }
                PoseStack poseStack = new PoseStack();
                poseStack.translate(offsetX, offsetY, offsetZ);

                // Create capture buffer
                CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, blockEntity);

                // Render the block entity
                renderer.render(
                    blockEntity,
                    0.0f,  // partialTick
                    poseStack,
                    captureBuffer,
                    0xF000F0,  // packedLight (full bright)
                    OverlayTexture.NO_OVERLAY
                );

                // Flush any remaining quads
                captureBuffer.flush();

                boolean hadGeometry = captureBuffer.hadGeometry();
                success.set(hadGeometry);
                com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] Render complete: hadGeometry=" + hadGeometry);
            } catch (Exception e) {
                com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] Render error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                OVERRIDES.remove();
            }
        });

        boolean result = success.get();
        com.voxelbridge.util.ExportLogger.log("[BlockEntityRenderer] Final result: " + result);
        return result;
    }

    /**
     * Captures rendered geometry from BlockEntity renderers.
     */
    private static class CaptureBuffer implements MultiBufferSource {
        private final ExportContext ctx;
        private final SceneSink sceneSink;
        private final double offsetX, offsetY, offsetZ;
        private final BlockEntity blockEntity;
        private final Map<RenderType, VertexCollector> collectors = new HashMap<>();
        private boolean hadGeometry = false;
        private final TextureOverrideMap overrides;

        CaptureBuffer(ExportContext ctx, SceneSink sceneSink, double offsetX, double offsetY, double offsetZ, BlockEntity blockEntity) {
            this.ctx = ctx;
            this.sceneSink = sceneSink;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.blockEntity = blockEntity;
            this.overrides = OVERRIDES.get();
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return collectors.computeIfAbsent(renderType, rt -> new VertexCollector(this, rt));
        }

        void flush() {
            for (VertexCollector collector : collectors.values()) {
                collector.flush();
            }
        }

        boolean hadGeometry() {
            return hadGeometry;
        }

        void recordGeometry() {
            this.hadGeometry = true;
        }

        TextureOverrideMap overrides() {
            return overrides;
        }

        /**
         * Collects vertices and outputs quads to SceneSink.
         */
        private static class VertexCollector implements VertexConsumer {
            private final CaptureBuffer parent;
            private final RenderType renderType;
            private final List<Vertex> vertices = new ArrayList<>(4);

            VertexCollector(CaptureBuffer parent, RenderType renderType) {
                this.parent = parent;
                this.renderType = renderType;
            }

            private static class Vertex {
                float x, y, z;
                float u, v;
                int color = 0xFFFFFFFF;

                Vertex(float x, float y, float z) {
                    this.x = x;
                    this.y = y;
                    this.z = z;
                }
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                if (vertices.size() < 4) {
                    vertices.add(new Vertex(x, y, z));
                    com.voxelbridge.util.ExportLogger.log("[VertexCollector] addVertex: (" + x + "," + y + "," + z + ") - now have " + vertices.size() + " vertices");
                }
                return this;
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a) {
                if (!vertices.isEmpty()) {
                    Vertex last = vertices.get(vertices.size() - 1);
                    last.color = (a << 24) | (r << 16) | (g << 8) | b;
                }
                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                if (!vertices.isEmpty()) {
                    Vertex last = vertices.get(vertices.size() - 1);
                    last.u = u;
                    last.v = v;
                    com.voxelbridge.util.ExportLogger.log("[VertexCollector] setUv called: u=" + u + ", v=" + v + " for vertex " + (vertices.size() - 1));
                }
                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }

            @Override
            public VertexConsumer setNormal(float nx, float ny, float nz) {
                // Normal will be computed from positions, so we ignore the input
                BlockEntityDebugLogger.log("[VertexCollector] setNormal called, vertices.size=" + vertices.size());

                // When we have 4 vertices with all attributes set, emit the quad
                if (vertices.size() >= 4) {
                    BlockEntityDebugLogger.log("[VertexCollector] All 4 vertices complete, emitting quad");
                    emitQuad();
                }

                return this;
            }

            private void emitQuad() {
                if (vertices.size() >= 4) {
                    outputQuad();
                    vertices.clear();
                } else {
                    BlockEntityDebugLogger.log("[VertexCollector][WARN] emitQuad called but only " + vertices.size() + " vertices collected!");
                }
            }

            void flush() {
                if (vertices.size() >= 3) {
                    outputQuad();
                    vertices.clear();
                }
            }

            private void outputQuad() {
                if (vertices.size() < 3) return;

                BlockEntityDebugLogger.log("[VertexCollector] ========== OUTPUT QUAD START ==========");
                BlockEntityDebugLogger.log("[VertexCollector] Vertices count: " + vertices.size());

                // Check for degenerate quad (zero or near-zero area)
                if (vertices.size() >= 3) {
                    float area = computeQuadArea(vertices);
                    if (area < 0.0001f) {
                        BlockEntityDebugLogger.log("[VertexCollector] Skipping degenerate quad (area=" + area + ")");
                        return;
                    }
                }

                parent.recordGeometry();

                // Build position and color arrays (UV will be filled later after texture resolution)
                float[] positions = new float[12];
                float[] uv0 = new float[8];
                float[] colors = new float[16];

                for (int i = 0; i < Math.min(4, vertices.size()); i++) {
                    Vertex v = vertices.get(i);
                    positions[i * 3] = v.x;
                    positions[i * 3 + 1] = v.y;
                    positions[i * 3 + 2] = v.z;

                    // Extract RGBA from packed color
                    colors[i * 4] = ((v.color >> 16) & 0xFF) / 255.0f;  // R
                    colors[i * 4 + 1] = ((v.color >> 8) & 0xFF) / 255.0f;   // G
                    colors[i * 4 + 2] = (v.color & 0xFF) / 255.0f;          // B
                    colors[i * 4 + 3] = ((v.color >> 24) & 0xFF) / 255.0f;  // A
                }

                // Extract texture from RenderType and register it
                BlockEntityTextureResolver.ResolvedTexture textureRes = BlockEntityTextureResolver.resolve(parent.blockEntity, renderType);
                TextureOverrideMap overrides = parent.overrides();
                String spriteKey;
                boolean isAtlasTexture = false;
                float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
                ResourceLocation atlasLocation = textureRes != null ? textureRes.atlasLocation() : null;

                int vertCount = Math.min(4, vertices.size());
                float[] rawU = new float[vertCount];
                float[] rawV = new float[vertCount];
                float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
                float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < vertCount; i++) {
                    rawU[i] = vertices.get(i).u;
                    rawV[i] = vertices.get(i).v;
                    minU = Math.min(minU, rawU[i]); maxU = Math.max(maxU, rawU[i]);
                    minV = Math.min(minV, rawV[i]); maxV = Math.max(maxV, rawV[i]);
                }
                // Wrapped UV for atlas lookup
                float[] wrappedU = new float[vertCount];
                float[] wrappedV = new float[vertCount];
                for (int i = 0; i < vertCount; i++) {
                    wrappedU[i] = wrap01(rawU[i]);
                    wrappedV[i] = wrap01(rawV[i]);
                }

                // Handle atlas texture resolution via locator if needed
                if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                    float centerU = average(wrappedU);
                    float centerV = average(wrappedV);
                    ResourceLocation atlas = atlasLocation != null ? atlasLocation : textureRes.texture();
                    TextureAtlasSprite located = ATLAS_LOCATOR.find(atlas, centerU, centerV);
                    if (located != null) {
                        textureRes = new BlockEntityTextureResolver.ResolvedTexture(
                            located.contents().name(),
                            located.getU0(), located.getU1(),
                            located.getV0(), located.getV1(),
                            true,
                            located,
                            atlas);
                        atlasLocation = atlas;
                    }
                }

                // Generate Material Group Key
                // Format: "blockentity:minecraft:chest"
                String materialGroupKey = "blockentity:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(parent.blockEntity.getType()).toString();

                if (textureRes != null && overrides != null) {
                    if (overrides.skipQuad(textureRes.texture(), rawU, rawV)) return;

                    var mappedHandle = overrides.resolve(textureRes.texture());
                    if (mappedHandle != null) {
                        spriteKey = mappedHandle.spriteKey();

                        // Use the resolved atlas bounds so fillUvs() can denormalize atlas-space UVs correctly
                        isAtlasTexture = textureRes.isAtlasTexture();
                        u0 = textureRes.u0(); u1 = textureRes.u1();
                        v0 = textureRes.v0(); v1 = textureRes.v1();

                        // IMPORTANT: Keep original isAtlasTexture and bounds to allow fillUvs()
                        // to correctly denormalize atlas-space UVs to sprite-space.
                        // Do NOT force isAtlasTexture=false here, as some overrides (e.g., Banner)
                        // may still have UVs in atlas-space that need denormalization.
                        // (keep original isAtlasTexture, u0, u1, v0, v1 values from textureRes)

                        fillUvs(vertices, uv0, isAtlasTexture, u0, u1, v0, v1);
                        parent.sceneSink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent", positions, uv0, EMPTY_UV, null, null, NORMAL_UP, colors,
                            com.voxelbridge.export.exporter.blockentity.RenderTypeTextureResolver.isDoubleSided(renderType));
                        return;
                    }
                }

                if (textureRes != null && textureRes.texture() != null) {
                    spriteKey = com.voxelbridge.export.texture.BlockEntityTextureManager.registerTexture(parent.ctx, textureRes);
                    isAtlasTexture = textureRes.isAtlasTexture();
                    u0 = textureRes.u0(); u1 = textureRes.u1();
                    v0 = textureRes.v0(); v1 = textureRes.v1();
                } else {
                    spriteKey = "blockentity:minecraft/block/white";
                }

                // Heuristic: if renderer passed sprite-space UVs (0..1) but texture came from an atlas,
                // switch to sprite-space normalization to avoid sampling the whole atlas.
                if (isAtlasTexture && textureRes != null && textureRes.sprite() != null) {
                    float eps = 1e-4f;
                    boolean outsideSpriteBounds =
                        minU < textureRes.u0() - eps || maxU > textureRes.u1() + eps ||
                        minV < textureRes.v0() - eps || maxV > textureRes.v1() + eps;
                    if (outsideSpriteBounds) {
                        isAtlasTexture = false;
                        u0 = 0f; u1 = 1f;
                        v0 = 0f; v1 = 1f;
                    }
                }

                fillUvs(vertices, uv0, isAtlasTexture, u0, u1, v0, v1);

                // NOTE: UV remapping to atlas space is handled by GltfSceneBuilder.remapUV()
                // to avoid double transformation. Do NOT remap UVs here.
                // Keep UVs in sprite space (0-1) for now, consistent with BlockExporter and FluidExporter.

                // Send quad to scene sink using the BlockEntity type as group key (block entities typically don't have overlays)
                parent.sceneSink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent", positions, uv0, EMPTY_UV, null, null, NORMAL_UP, colors,
                    com.voxelbridge.export.exporter.blockentity.RenderTypeTextureResolver.isDoubleSided(renderType));
            }

            private void fillUvs(List<Vertex> verts, float[] uv0, boolean isAtlas, float u0, float u1, float v0, float v1) {
                int count = Math.min(4, verts.size());
                if (isAtlas) {
                    float du = u1 - u0;
                    float dv = v1 - v0;
                    for (int i = 0; i < count; i++) {
                        Vertex v = verts.get(i);
                        float su = (du == 0f) ? 0f : (v.u - u0) / du;
                        float sv = (dv == 0f) ? 0f : (v.v - v0) / dv;
                        su = Math.max(0f, Math.min(1f, su));
                        sv = Math.max(0f, Math.min(1f, sv));
                        uv0[i * 2] = su;
                        uv0[i * 2 + 1] = sv;
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        Vertex v = verts.get(i);
                        float su = Math.max(0f, Math.min(1f, v.u));
                        float sv = Math.max(0f, Math.min(1f, v.v));
                        uv0[i * 2] = su;
                        uv0[i * 2 + 1] = sv;
                    }
                }
            }

            private float average(float[] arr) {
                if (arr == null || arr.length == 0) return 0f;
                float sum = 0f;
                for (float v : arr) sum += v;
                return sum / arr.length;
            }

            private float wrap01(float v) {
                float wrapped = v % 1f;
                if (wrapped < 0f) wrapped += 1f;
                return wrapped;
            }

            private float computeQuadArea(List<Vertex> verts) {
                if (verts.size() < 3) return 0f;
                Vertex v0 = verts.get(0);
                Vertex v1 = verts.get(1);
                Vertex v2 = verts.get(2);
                float ax = v1.x - v0.x; float ay = v1.y - v0.y; float az = v1.z - v0.z;
                float bx = v2.x - v0.x; float by = v2.y - v0.y; float bz = v2.z - v0.z;
                float cx = ay * bz - az * by;
                float cy = az * bx - ax * bz;
                float cz = ax * by - ay * bx;
                return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            }
        }
    }
}
