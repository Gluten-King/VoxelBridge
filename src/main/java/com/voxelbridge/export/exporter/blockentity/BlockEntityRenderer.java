package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        RenderTask task = createTask(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides);
        if (task == null) {
            return false;
        }
        Minecraft.getInstance().executeBlocking(task);
        return task.wasSuccessful();
    }

    /**
     * Creates a render task that can be executed later (ideally on the main thread).
     */
    public static RenderTask createTask(
        ExportContext ctx,
        BlockEntity blockEntity,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides
    ) {
        com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Attempting to render BlockEntity: " + blockEntity.getClass().getSimpleName() + " at " + blockEntity.getBlockPos());
        BlockEntityRenderDispatcher dispatcher = ctx.getMc().getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer =
                dispatcher.getRenderer(blockEntity);

        if (renderer == null) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] No renderer found for: " + blockEntity.getClass().getSimpleName());
            return null;
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Found renderer: " + renderer.getClass().getSimpleName());
        BlockPos pos = blockEntity.getBlockPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return new RenderTask(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides, renderer, chunkX, chunkZ);
    }

    /**
     * Executes render logic without scheduling. Caller must run on the main thread.
     */
    private static boolean renderDirect(
        ExportContext ctx,
        BlockEntity blockEntity,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides,
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer
    ) {
        try {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer][renderDirect] Starting render for " + blockEntity.getClass().getSimpleName());
            if (overrides != null) {
                OVERRIDES.set(overrides);
            }
            PoseStack poseStack = new PoseStack();
            poseStack.translate(offsetX, offsetY, offsetZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, blockEntity);

            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer][renderDirect] Calling renderer.render()...");
            renderer.render(
                blockEntity,
                0.0f,
                poseStack,
                captureBuffer,
                0xF000F0,
                OverlayTexture.NO_OVERLAY
            );

            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer][renderDirect] renderer.render() returned, flushing buffer...");
            captureBuffer.flush();

            boolean hadGeometry = captureBuffer.hadGeometry();
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Render complete: hadGeometry=" + hadGeometry);
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Final result: " + hadGeometry);
            return hadGeometry;
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Render error: " + e.getMessage());
            e.printStackTrace();
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Final result: false");
            return false;
        } finally {
            OVERRIDES.remove();
        }
    }

    /**
     * Task wrapper for batch execution.
     */
    public static final class RenderTask implements Runnable {
        private final ExportContext ctx;
        private final BlockEntity blockEntity;
        private final SceneSink sceneSink;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final TextureOverrideMap overrides;
        private final net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer;
        private final int chunkX;
        private final int chunkZ;
        private boolean success;

        RenderTask(
            ExportContext ctx,
            BlockEntity blockEntity,
            SceneSink sceneSink,
            double offsetX,
            double offsetY,
            double offsetZ,
            TextureOverrideMap overrides,
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer<BlockEntity> renderer,
            int chunkX,
            int chunkZ
        ) {
            this.ctx = ctx;
            this.blockEntity = blockEntity;
            this.sceneSink = sceneSink;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.overrides = overrides;
            this.renderer = renderer;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public void run() {
            sceneSink.onChunkStart(chunkX, chunkZ);
            try {
                this.success = renderDirect(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides, renderer);
            } finally {
                sceneSink.onChunkEnd(chunkX, chunkZ, this.success);
            }
        }

        public boolean wasSuccessful() {
            return success;
        }
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
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[CaptureBuffer] getBuffer() called for RenderType: " + renderType);
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
            private final java.util.ArrayDeque<Vertex> vertices = new java.util.ArrayDeque<>(8);
            private final com.mojang.blaze3d.vertex.VertexFormat.Mode mode;
            private final int vertsPerPrimitive;

            VertexCollector(CaptureBuffer parent, RenderType renderType) {
                this.parent = parent;
                this.renderType = renderType;
                this.mode = renderType.mode();
                this.vertsPerPrimitive = switch (mode) {
                    case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> 3;
                    case QUADS -> 4;
                    default -> 4;
                };
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
                vertices.addLast(new Vertex(x, y, z));
                return this;
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a) {
                Vertex last = vertices.peekLast();
                if (last != null) {
                    last.color = (a << 24) | (r << 16) | (g << 8) | b;
                }
                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                Vertex last = vertices.peekLast();
                if (last != null) {
                    last.u = u;
                    last.v = v;
                }
                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }

            @Override
            public VertexConsumer setNormal(float nx, float ny, float nz) {
                // Normal will be computed from positions, so we ignore the input
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] setNormal called, vertices.size=" + vertices.size());
                }

                emitReadyPrimitives(false);

                return this;
            }

            void flush() {
                emitReadyPrimitives(true);
            }

            private void emitReadyPrimitives(boolean flushRemainder) {
                // Emit full primitives (quads or triangles depending on renderType.mode())
                while (vertices.size() >= vertsPerPrimitive) {
                    outputQuad(extractPrimitive(vertsPerPrimitive));
                }
                // On final flush, if renderer left an incomplete primitive (>=3 verts), output best-effort
                if (flushRemainder && vertices.size() >= 3) {
                    outputQuad(extractPrimitive(vertices.size()));
                }
            }

            private List<Vertex> extractPrimitive(int count) {
                List<Vertex> prim = new ArrayList<>(count);
                for (int i = 0; i < count && !vertices.isEmpty(); i++) {
                    prim.add(vertices.removeFirst());
                }
                return prim;
            }

            private void outputQuad(List<Vertex> verts) {
                if (verts.size() < 3) return;

                boolean logQuads = VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY);
                if (logQuads) {
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] ========== OUTPUT QUAD START ==========");
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] Vertices count: " + verts.size());
                }

                // Check for degenerate quad (zero or near-zero area)
                if (verts.size() >= 3) {
                    float area = computeQuadArea(verts);
                    if (area < 0.0001f) {
                        if (logQuads) {
                            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] Skipping degenerate quad (area=" + area + ")");
                        }
                        return;
                    }
                }

                parent.recordGeometry();

                // Build position and color arrays (UV will be filled later after texture resolution)
                float[] positions = new float[12];
                float[] uv0 = new float[8];
                float[] colors = new float[16];

                for (int i = 0; i < Math.min(4, verts.size()); i++) {
                    Vertex v = verts.get(i);
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

                int vertCount = Math.min(4, verts.size());
                float[] rawU = new float[vertCount];
                float[] rawV = new float[vertCount];
                float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
                float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < vertCount; i++) {
                    rawU[i] = verts.get(i).u;
                    rawV[i] = verts.get(i).v;
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

                        fillUvs(verts, uv0, isAtlasTexture, u0, u1, v0, v1);

                        float[] uv1 = EMPTY_UV;
                        if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.VERTEX_COLOR) {
                            // keep vertex colors, uv1 empty
                        } else {
                            int argb = toArgb(colors);
                            boolean hasTint = !isWhite(colors);
                            ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(parent.ctx, argb, hasTint);
                            uv1 = colorData.uv1() != null ? colorData.uv1() : EMPTY_UV;
                            System.arraycopy(GeometryUtil.whiteColor(), 0, colors, 0, colors.length);
                        }

                        parent.sceneSink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent", positions, uv0, uv1, NORMAL_UP, colors,
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

                fillUvs(verts, uv0, isAtlasTexture, u0, u1, v0, v1);

                // NOTE: UV remapping to atlas space is handled by GltfSceneBuilder.remapUV()
                // to avoid double transformation. Do NOT remap UVs here.
                // Keep UVs in sprite space (0-1) for now, consistent with BlockExporter and FluidExporter.

                // Color/colormap handling to mirror BlockExporter behavior:
                // - ColorMap mode: always provide TEXCOORD_1; no tint -> white slot.
                // - VertexColor mode: keep per-vertex colors (already in 'colors').
                float[] uv1 = EMPTY_UV;
                if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.VERTEX_COLOR) {
                    // colors already populated from vertex data; keep uv1 empty
                } else {
                    // Derive a single ARGB from the first vertex color (linear -> srgb approximation)
                    int argb = toArgb(colors);
                    boolean hasTint = !isWhite(colors);
                    ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(parent.ctx, argb, hasTint);
                    uv1 = colorData.uv1() != null ? colorData.uv1() : EMPTY_UV;
                    // Force colors to white in colormap mode
                    System.arraycopy(GeometryUtil.whiteColor(), 0, colors, 0, colors.length);
                }

                // Send quad to scene sink using the BlockEntity type as group key (block entities typically don't have overlays)
                parent.ctx.registerSpriteMaterial(spriteKey, materialGroupKey);
                parent.sceneSink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent", positions, uv0, uv1, NORMAL_UP, colors,
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

            private int toArgb(float[] colors) {
                // colors length >=4; clamp to [0,1] then convert to 0xAARRGGBB with alpha=1
                float r = colors.length >= 1 ? colors[0] : 1f;
                float g = colors.length >= 2 ? colors[1] : 1f;
                float b = colors.length >= 3 ? colors[2] : 1f;
                int ri = (int) (Math.max(0f, Math.min(1f, r)) * 255f + 0.5f);
                int gi = (int) (Math.max(0f, Math.min(1f, g)) * 255f + 0.5f);
                int bi = (int) (Math.max(0f, Math.min(1f, b)) * 255f + 0.5f);
                return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
            }

            private boolean isWhite(float[] colors) {
                if (colors == null || colors.length < 3) return true;
                float eps = 1e-3f;
                return Math.abs(colors[0] - 1f) < eps &&
                       Math.abs(colors[1] - 1f) < eps &&
                       Math.abs(colors[2] - 1f) < eps;
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




