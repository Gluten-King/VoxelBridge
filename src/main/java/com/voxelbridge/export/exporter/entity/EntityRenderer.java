package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.export.exporter.blockentity.RenderTypeTextureResolver;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures entity renderer output into SceneSink.
 */
@OnlyIn(Dist.CLIENT)
public final class EntityRenderer {

    private static final float[] EMPTY_UV = new float[8];
    private static final float[] NORMAL_UP = new float[] {
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f
    };
    private static final EntityAtlasLocator ATLAS_LOCATOR = new EntityAtlasLocator(Minecraft.getInstance());

    private EntityRenderer() {}

    public static boolean render(
        ExportContext ctx,
        Entity entity,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        try {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityRenderer] Starting render for " + entity.getType());
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                net.minecraft.world.phys.Vec3 pos = entity.position();
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Position] %s at world[%.3f, %.3f, %.3f] offset[%.3f, %.3f, %.3f] final[%.3f, %.3f, %.3f]",
                    entity.getType(),
                    pos.x, pos.y, pos.z,
                    offsetX, offsetY, offsetZ,
                    pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ));
            }

            EntityRenderDispatcher dispatcher = ctx.getMc().getEntityRenderDispatcher();
            net.minecraft.client.renderer.entity.EntityRenderer<? super Entity> renderer =
                (net.minecraft.client.renderer.entity.EntityRenderer<? super Entity>) dispatcher.getRenderer(entity);
            if (renderer == null) {
                VoxelBridgeLogger.error(LogModule.ENTITY, String.format(
                    "[ERROR] %s at [%.2f, %.2f, %.2f] - %s",
                    entity.getType(),
                    entity.position().x,
                    entity.position().y,
                    entity.position().z,
                    "No renderer available"));
                return false;
            }

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityRenderer] Using renderer: " + renderer.getClass().getSimpleName());

            PoseStack poseStack = new PoseStack();

            // Calculate base position
            double finalX = entity.getX() + offsetX;
            double finalY = entity.getY() + offsetY;
            double finalZ = entity.getZ() + offsetZ;

            // Apply direction-based offset for hanging entities (paintings, item frames)
            if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hangingEntity) {
                double[] hangingOffset = HangingEntityPositionUtil.calculateRenderOffset(hangingEntity);
                finalX += hangingOffset[0];
                finalY += hangingOffset[1];
                finalZ += hangingOffset[2];

                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[HangingEntity] Applied direction offset: direction=%s offset=[%.4f, %.4f, %.4f]",
                    hangingEntity.getDirection(), hangingOffset[0], hangingOffset[1], hangingOffset[2]));
            }

            poseStack.translate(finalX, finalY, finalZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, entity);
            float partial = 0f;
            float yaw = entity.getYRot();
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Rotation] %s yaw=%.2f鎺?(actual=%.2f鎺? isHanging=%s)",
                    entity.getType(),
                    yaw,
                    entity.getYRot(),
                    entity instanceof net.minecraft.world.entity.decoration.HangingEntity));
            }

            // Use max light level for better visibility
            int packedLight = 0xF000F0;

            boolean[] renderCompleted = new boolean[1];
            Exception[] renderException = new Exception[1];

            ctx.getMc().executeBlocking(() -> {
                try {
                    // 妫板嫭濮峰☉?renderer 閸愬懘鍎撮惃?getRenderOffset閿涘牅绶ユ俊?ItemFrameRenderer 閻?0.3/-0.25 閸嬪繒些閿涘绱濋柆鍨帳娴ｅ秶鐤嗛柌宥咁槻閸嬪繒些
                    var renderOffset = renderer.getRenderOffset(entity, partial);
                    poseStack.translate(renderOffset.x(), renderOffset.y(), renderOffset.z());

                    renderer.render(
                        entity,
                        yaw,
                        partial,
                        poseStack,
                        captureBuffer,
                        packedLight
                    );
                    renderCompleted[0] = true;
                } catch (Exception e) {
                    renderException[0] = e;
                }
            });

            if (renderException[0] != null) {
                VoxelBridgeLogger.error(LogModule.ENTITY, String.format(
                    "[ERROR] %s at [%.2f, %.2f, %.2f] - %s",
                    entity.getType(),
                    entity.position().x,
                    entity.position().y,
                    entity.position().z,
                    "Render exception: " + renderException[0].getMessage()), renderException[0]);
            }

            captureBuffer.flush();
            boolean hadGeometry = captureBuffer.hadGeometry();

            if (hadGeometry) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityRenderer] Successfully captured geometry for " + entity.getType());
            } else {
                VoxelBridgeLogger.warn(LogModule.ENTITY, String.format(
                    "[NoGeometry] %s at [%.2f, %.2f, %.2f] - %s",
                    entity.getType(),
                    entity.position().x,
                    entity.position().y,
                    entity.position().z,
                    "No vertices were captured during render"));
            }

            return hadGeometry;
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.ENTITY, String.format(
                "[ERROR] %s at [%.2f, %.2f, %.2f] - %s",
                entity.getType(),
                entity.position().x,
                entity.position().y,
                entity.position().z,
                "Unexpected error: " + e.getMessage()), e);
            return false;
        }
    }

    private static boolean isHangingEntity(Entity entity) {
        return entity instanceof net.minecraft.world.entity.decoration.HangingEntity;
    }

    /**
     * Capture buffer for entity renders.
     */
    private static class CaptureBuffer implements MultiBufferSource {
        private final ExportContext ctx;
        private final SceneSink sceneSink;
        private final double offsetX, offsetY, offsetZ;
        private final Entity entity;
        private final java.util.Map<RenderType, VertexCollector> collectors = new java.util.HashMap<>();
        private boolean hadGeometry = false;
        private int quadCount = 0;
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        CaptureBuffer(ExportContext ctx, SceneSink sceneSink, double offsetX, double offsetY, double offsetZ, Entity entity) {
            this.ctx = ctx;
            this.sceneSink = sceneSink;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.entity = entity;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return collectors.computeIfAbsent(renderType, rt -> new VertexCollector(this, rt));
        }

        void flush() {
            for (VertexCollector collector : collectors.values()) {
                collector.flush();
            }
            if (hadGeometry) {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[Geometry] %s vertices=%d quads=%d",
                        entity.getType(), quadCount * 4, quadCount));
                }
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Bounds] min[%.4f, %.4f, %.4f] max[%.4f, %.4f, %.4f]",
                    minX, minY, minZ, maxX, maxY, maxZ));
            }
        }

        boolean hadGeometry() {
            return hadGeometry;
        }

        void recordGeometry() {
            this.hadGeometry = true;
        }

        /**
         * Collects vertices and outputs quads to SceneSink.
         */
        private static class VertexCollector implements VertexConsumer {
            private final CaptureBuffer parent;
            private final RenderType renderType;
            private final ArrayDeque<Vertex> vertices = new ArrayDeque<>(8);
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

            @Override public VertexConsumer setUv1(int u, int v) { return this; }
            @Override public VertexConsumer setUv2(int u, int v) { return this; }

            @Override
            public VertexConsumer setNormal(float nx, float ny, float nz) {
                emitReadyPrimitives(false);
                return this;
            }

            void flush() {
                emitReadyPrimitives(true);
            }

            private void emitReadyPrimitives(boolean flushRemainder) {
                while (vertices.size() >= vertsPerPrimitive) {
                    outputQuad(extractPrimitive(vertsPerPrimitive));
                }
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

                parent.recordGeometry();
                parent.quadCount++;

                float[] positions = new float[12];
                float[] uv0 = new float[8];
                float[] colors = new float[16];

                for (int i = 0; i < Math.min(4, verts.size()); i++) {
                    Vertex v = verts.get(i);
                    positions[i * 3] = v.x;
                    positions[i * 3 + 1] = v.y;
                    positions[i * 3 + 2] = v.z;

                    parent.minX = Math.min(parent.minX, v.x);
                    parent.minY = Math.min(parent.minY, v.y);
                    parent.minZ = Math.min(parent.minZ, v.z);
                    parent.maxX = Math.max(parent.maxX, v.x);
                    parent.maxY = Math.max(parent.maxY, v.y);
                    parent.maxZ = Math.max(parent.maxZ, v.z);

                    colors[i * 4] = ((v.color >> 16) & 0xFF) / 255.0f;
                    colors[i * 4 + 1] = ((v.color >> 8) & 0xFF) / 255.0f;
                    colors[i * 4 + 2] = (v.color & 0xFF) / 255.0f;
                    colors[i * 4 + 3] = ((v.color >> 24) & 0xFF) / 255.0f;
                }

                if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[Quad#%d] Vertices: v0=[%.3f,%.3f,%.3f] v1=[%.3f,%.3f,%.3f]",
                        parent.quadCount,
                        positions[0], positions[1], positions[2],
                        positions[3], positions[4], positions[5]));
                }

                if (VoxelBridgeLogger.isTraceEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.trace(LogModule.ENTITY, String.format(
                        "[RenderType] %s renderType=%s",
                        parent.entity.getType(),
                        renderType != null ? renderType.toString() : "null"));
                }
                EntityTextureResolver.ResolvedTexture textureRes = EntityTextureResolver.resolve(parent.entity, renderType);
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
                float[] wrappedU = new float[vertCount];
                float[] wrappedV = new float[vertCount];
                for (int i = 0; i < vertCount; i++) {
                    wrappedU[i] = wrap01(rawU[i]);
                    wrappedV[i] = wrap01(rawV[i]);
                }

                if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                    float centerU = average(wrappedU);
                    float centerV = average(wrappedV);
                    ResourceLocation atlas = atlasLocation != null ? atlasLocation : textureRes.texture();
                    TextureAtlasSprite located = ATLAS_LOCATOR.find(atlas, centerU, centerV);
                    if (located != null) {
                        textureRes = new EntityTextureResolver.ResolvedTexture(
                            located.contents().name(),
                            located.getU0(), located.getU1(),
                            located.getV0(), located.getV1(),
                            true,
                            located,
                            atlas);
                        atlasLocation = atlas;
                    }
                }

                String materialGroupKey = "entity:" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(parent.entity.getType());

                if (textureRes != null && textureRes.texture() != null) {
                    EntityTextureManager.TextureHandle handle = EntityTextureManager.register(parent.ctx, textureRes.texture());
                    spriteKey = handle.spriteKey();
                    isAtlasTexture = textureRes.isAtlasTexture();
                    u0 = textureRes.u0(); u1 = textureRes.u1();
                    v0 = textureRes.v0(); v1 = textureRes.v1();

                    if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                        VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                            "[Texture] %s texture=%s isAtlas=%s",
                            parent.entity.getType(),
                            textureRes.texture() != null ? textureRes.texture() : "null",
                            isAtlasTexture));
                    }
                    if (VoxelBridgeLogger.isTraceEnabled(LogModule.ENTITY)) {
                        VoxelBridgeLogger.trace(LogModule.ENTITY, String.format(
                            "[UV] %s u=[%.4f, %.4f] v=[%.4f, %.4f]",
                            parent.entity.getType(), u0, u1, v0, v1));
                    }

                    // 缂傛挸鐡╝tlas sprite閸ユ儳鍎氶敍灞肩返閸氬海鐢籥tlas閻㈢喐鍨氭担璺ㄦ暏
                    if (isAtlasTexture && textureRes.sprite() != null) {
                        BufferedImage spriteImg = com.voxelbridge.export.texture.TextureLoader.fromSprite(textureRes.sprite());
                        if (spriteImg != null) {
                            parent.ctx.cacheSpriteImage(spriteKey, spriteImg);
                        }
                    }
                } else {
                    spriteKey = "entity:minecraft/white";
                    VoxelBridgeLogger.debug(LogModule.ENTITY, "[Quad#" + parent.quadCount + "] No texture resolved, using white fallback");
                }

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

                float[] uv1 = EMPTY_UV;
                if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.VERTEX_COLOR) {
                    // keep vertex colors
                } else {
                    int argb = toArgb(colors);
                    boolean hasTint = !isWhite(colors);
                    ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(parent.ctx, argb, hasTint);
                    uv1 = colorData.uv1 != null ? colorData.uv1 : EMPTY_UV;
                    System.arraycopy(GeometryUtil.whiteColor(), 0, colors, 0, colors.length);
                }

                parent.ctx.registerSpriteMaterial(spriteKey, materialGroupKey);
                parent.sceneSink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent",
                    positions, uv0, uv1, NORMAL_UP, colors,
                    RenderTypeTextureResolver.isDoubleSided(renderType));
            }

            private void fillUvs(List<Vertex> verts, float[] uv0, boolean isAtlas, float u0, float u1, float v0, float v1) {
                int count = Math.min(4, verts.size());
                if (isAtlas) {
                    // Atlas texture: normalize UV within sprite bounds
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
                    // Non-atlas texture: find actual UV bounds and normalize
                    // This is important for entities like paintings where UV may not be in [0,1]
                    float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
                    float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                    for (int i = 0; i < count; i++) {
                        Vertex v = verts.get(i);
                        minU = Math.min(minU, v.u);
                        maxU = Math.max(maxU, v.u);
                        minV = Math.min(minV, v.v);
                        maxV = Math.max(maxV, v.v);
                    }

                    float rangeU = maxU - minU;
                    float rangeV = maxV - minV;

                    // IMPROVED: More robust detection with wider threshold
                    // If UV extends significantly beyond [0,1] OR has zero range, normalize
                    boolean needsNormalization =
                        maxU > 1.1f || minU < -0.1f || maxV > 1.1f || minV < -0.1f ||
                        rangeU < 1e-6f || rangeV < 1e-6f;

                    if (needsNormalization && rangeU > 1e-6f && rangeV > 1e-6f) {
                        // Normalize from [minU, maxU] x [minV, maxV] to [0, 1] x [0, 1]
                        VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                            "[UV Normalization] Painting/Entity UV remapped: U[%.3f, %.3f] V[%.3f, %.3f] -> [0,1]x[0,1]",
                            minU, maxU, minV, maxV));
                        for (int i = 0; i < count; i++) {
                            Vertex v = verts.get(i);
                            float su = (v.u - minU) / rangeU;
                            float sv = (v.v - minV) / rangeV;
                            uv0[i * 2] = Math.max(0f, Math.min(1f, su));
                            uv0[i * 2 + 1] = Math.max(0f, Math.min(1f, sv));
                        }
                    } else if (rangeU < 1e-6f || rangeV < 1e-6f) {
                        // Degenerate UV (zero range) - use defaults
                        VoxelBridgeLogger.debug(LogModule.ENTITY, "[UV Normalization] Degenerate UV detected, using [0,0] for all vertices");
                        for (int i = 0; i < count; i++) {
                            uv0[i * 2] = 0f;
                            uv0[i * 2 + 1] = 0f;
                        }
                    } else {
                        // Already in [0,1], just clamp
                        for (int i = 0; i < count; i++) {
                            Vertex v = verts.get(i);
                            uv0[i * 2] = Math.max(0f, Math.min(1f, v.u));
                            uv0[i * 2 + 1] = Math.max(0f, Math.min(1f, v.v));
                        }
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
        }
    }
}

