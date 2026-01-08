package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderLayerTextureResolver;
import com.voxelbridge.platform.render.capture.CaptureBufferBase;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders BlockEntities and captures their geometry to an IR sink.
 */
public final class BlockEntityRenderer {

    private static final float[] EMPTY_UV = new float[8];
    private static final float[] NORMAL_UP = new float[] {
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f
    };
    private static AtlasLocator ATLAS_LOCATOR = new BlockEntityAtlasLocator(ClientAccessHolder.get());
    private static final ThreadLocal<TextureOverrideMap> OVERRIDES = new ThreadLocal<>();
    private static TextureResolver<BlockEntity> TEXTURE_RESOLVER = BlockEntityTextureResolver.INSTANCE;
    private static RenderTypeResolver RENDER_TYPE_RESOLVER = RenderLayerTextureResolver.INSTANCE;

    private BlockEntityRenderer() {}

    public static void setAtlasLocator(AtlasLocator locator) {
        if (locator != null) {
            ATLAS_LOCATOR = locator;
        }
    }

    public static void setTextureResolver(TextureResolver<BlockEntity> resolver) {
        if (resolver != null) {
            TEXTURE_RESOLVER = resolver;
        }
    }

    public static void setRenderTypeResolver(RenderTypeResolver resolver) {
        if (resolver != null) {
            RENDER_TYPE_RESOLVER = resolver;
        }
    }

    public static boolean render(
        ExportContext ctx,
        BlockEntity blockEntity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        return render(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, null);
    }

    public static boolean render(
        ExportContext ctx,
        BlockEntity blockEntity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides
    ) {
        RenderTask task = createTask(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides);
        if (task == null) {
            return false;
        }
        ctx.runOnMainThread(task);
        return task.wasSuccessful();
    }

    public static RenderTask createTask(
        ExportContext ctx,
        BlockEntity blockEntity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides
    ) {
        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
            "[BlockEntityRenderer] Attempting to render BlockEntity: " + blockEntity.getClass().getSimpleName() +
                " at " + blockEntity.getPos());
        BlockEntityRenderDispatcher dispatcher = ctx.getMc().getBlockEntityRenderDispatcher();
        net.minecraft.client.render.block.entity.BlockEntityRenderer<BlockEntity> renderer =
            dispatcher.get(blockEntity);

        if (renderer == null) {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer] No renderer found for: " + blockEntity.getClass().getSimpleName());
            return null;
        }

        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
            "[BlockEntityRenderer] Found renderer: " + renderer.getClass().getSimpleName());
        BlockPos pos = blockEntity.getPos();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return new RenderTask(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides, renderer, chunkX, chunkZ);
    }

    private static boolean renderDirect(
        ExportContext ctx,
        BlockEntity blockEntity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides,
        net.minecraft.client.render.block.entity.BlockEntityRenderer<BlockEntity> renderer
    ) {
        try {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer][renderDirect] Starting render for " + blockEntity.getClass().getSimpleName());
            if (overrides != null) {
                OVERRIDES.set(overrides);
            }
            MatrixStack matrices = new MatrixStack();
            matrices.translate(offsetX, offsetY, offsetZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, blockEntity);

            BlockEntityRenderDispatcher dispatcher = ctx.getMc().getBlockEntityRenderDispatcher();
            var mc = ctx.getMc();
            if (mc.world != null && mc.gameRenderer != null) {
                dispatcher.configure(mc.world, mc.gameRenderer.getCamera(), mc.crosshairTarget);
            }

            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer][renderDirect] Calling dispatcher.renderEntity()...");
            dispatcher.renderEntity(
                blockEntity,
                matrices,
                captureBuffer,
                0xF000F0,
                OverlayTexture.DEFAULT_UV
            );

            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer][renderDirect] dispatcher.renderEntity() returned, flushing buffer...");
            captureBuffer.flush();

            boolean hadGeometry = captureBuffer.hadGeometry();
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer] Render complete: hadGeometry=" + hadGeometry);
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                "[BlockEntityRenderer] Final result: " + hadGeometry);
            return hadGeometry;
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Render error: " + e.getMessage(), e);
            if (e instanceof net.minecraft.util.crash.CrashException crash) {
                try {
                    var report = crash.getReport();
                    String details = report.asString(net.minecraft.util.crash.ReportType.MINECRAFT_CRASH_REPORT);
                    VoxelBridgeLogger.error(LogModule.BLOCKENTITY, "[BlockEntityRenderer][CrashReport]\n" + details);
                } catch (Exception ignored) {
                }
            }
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Final result: false");
            return false;
        } finally {
            OVERRIDES.remove();
        }
    }

    public static final class RenderTask implements Runnable {
        private final ExportContext ctx;
        private final BlockEntity blockEntity;
        private final IrSink sceneSink;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final TextureOverrideMap overrides;
        private final net.minecraft.client.render.block.entity.BlockEntityRenderer<BlockEntity> renderer;
        private final int chunkX;
        private final int chunkZ;
        private boolean success;

        RenderTask(
            ExportContext ctx,
            BlockEntity blockEntity,
            IrSink sceneSink,
            double offsetX,
            double offsetY,
            double offsetZ,
            TextureOverrideMap overrides,
            net.minecraft.client.render.block.entity.BlockEntityRenderer<BlockEntity> renderer,
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

    private static class CaptureBuffer extends CaptureBufferBase {
        private static final Set<String> LOGGED_TEXT_TYPES = ConcurrentHashMap.newKeySet();
        private final BlockEntity blockEntity;
        private final TextureOverrideMap overrides;

        CaptureBuffer(ExportContext ctx, IrSink sceneSink, double offsetX, double offsetY, double offsetZ, BlockEntity blockEntity) {
            super(ctx, sceneSink, (renderLayer, queuedVertices) -> {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                        "[VertexCollector] setNormal called, vertices.size=" + queuedVertices);
                }
            });
            this.blockEntity = blockEntity;
            this.overrides = OVERRIDES.get();
        }

        void flush() {
            flushCapture();
        }

        TextureOverrideMap overrides() {
            return overrides;
        }

        @Override
        public void onQuad(net.minecraft.client.render.RenderLayer renderLayer, List<RenderCapture.Vertex> verts) {
            if (verts.size() < 3) return;

            if (shouldSkipTextQuad(renderLayer)) {
                return;
            }

            boolean logQuads = VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY);
            if (logQuads) {
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] ========== OUTPUT QUAD START ==========");
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] Vertices count: " + verts.size());
            }

            if (verts.size() >= 3) {
                float area = computeQuadArea(verts);
                if (area < 0.0001f) {
                    if (logQuads) {
                        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY,
                            "[VertexCollector] Skipping degenerate quad (area=" + area + ")");
                    }
                    return;
                }
            }

            recordGeometry();

            float[] positions = new float[12];
            float[] uv0 = new float[8];
            float[] colors = new float[16];

            for (int i = 0; i < Math.min(4, verts.size()); i++) {
                RenderCapture.Vertex v = verts.get(i);
                positions[i * 3] = v.x;
                positions[i * 3 + 1] = v.y;
                positions[i * 3 + 2] = v.z;

                colors[i * 4] = ((v.color >> 16) & 0xFF) / 255.0f;
                colors[i * 4 + 1] = ((v.color >> 8) & 0xFF) / 255.0f;
                colors[i * 4 + 2] = (v.color & 0xFF) / 255.0f;
                colors[i * 4 + 3] = ((v.color >> 24) & 0xFF) / 255.0f;
            }

            ResolvedTexture textureRes = TEXTURE_RESOLVER.resolve(blockEntity, renderLayer);
            TextureOverrideMap overrides = overrides();
            String spriteKey;
            boolean isAtlasTexture = false;
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
            Identifier atlasLocation = textureRes != null ? textureRes.atlasLocation() : null;

            RenderCaptureUtil.UvStats uvStats = RenderCaptureUtil.computeUvStats(verts);
            logTextUvOnce(renderLayer, uvStats);

            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(textureRes, ATLAS_LOCATOR, uvStats, atlasLocation);
                if (textureRes != null) {
                    atlasLocation = textureRes.atlasLocation();
                }
            }

            String materialGroupKey = "blockentity:" + Registries.BLOCK_ENTITY_TYPE
                .getId(blockEntity.getType()).toString();

            if (textureRes != null && overrides != null) {
                if (overrides.skipQuad(textureRes.texture(), uvStats.rawU(), uvStats.rawV())) return;

                var mappedHandle = overrides.resolve(textureRes.texture());
                if (mappedHandle != null) {
                    spriteKey = mappedHandle.spriteKey();

                    isAtlasTexture = textureRes.isAtlasTexture();
                    u0 = textureRes.u0(); u1 = textureRes.u1();
                    v0 = textureRes.v0(); v1 = textureRes.v1();

                    fillUvs(verts, uv0, isAtlasTexture, u0, u1, v0, v1);

                    String resolvedMaterialKey = ctx.resolveMaterialKey(spriteKey, materialGroupKey);
                    RenderCaptureUtil.ColorModeResult colorResult =
                        RenderCaptureUtil.applyColorMode(ctx, colors, EMPTY_UV);
                    ctx.registerSpriteMaterial(spriteKey, resolvedMaterialKey);
                    sceneSink.addQuad(resolvedMaterialKey, spriteKey, "voxelbridge:transparent",
                        RenderLayer.UNKNOWN, colorResult.tintMode(),
                        RENDER_TYPE_RESOLVER.isDoubleSided(renderLayer),
                        false,
                        positions, uv0, colorResult.uv1(), NORMAL_UP, colors);
                    return;
                }
            }

            if (textureRes != null && textureRes.texture() != null) {
                spriteKey = com.voxelbridge.export.texture.BlockEntityTextureManager.registerTexture(ctx, textureRes);
                isAtlasTexture = textureRes.isAtlasTexture();
                u0 = textureRes.u0(); u1 = textureRes.u1();
                v0 = textureRes.v0(); v1 = textureRes.v1();
            } else {
                spriteKey = "blockentity:minecraft/block/white";
            }

            if (isAtlasTexture && textureRes != null && textureRes.sprite() != null) {
                float eps = 1e-4f;
                boolean outsideSpriteBounds =
                    uvStats.minU() < textureRes.u0() - eps || uvStats.maxU() > textureRes.u1() + eps ||
                    uvStats.minV() < textureRes.v0() - eps || uvStats.maxV() > textureRes.v1() + eps;
                if (outsideSpriteBounds) {
                    isAtlasTexture = false;
                    u0 = 0f; u1 = 1f;
                    v0 = 0f; v1 = 1f;
                }
            }

            fillUvs(verts, uv0, isAtlasTexture, u0, u1, v0, v1);

            if (!isAtlasTexture && textureRes != null && (uvStats.maxU() > 1f || uvStats.maxV() > 1f)) {
                BufferedImage img = ctx.getCachedSpriteImage(spriteKey);
                if (img != null) {
                    fillUvsPixels(verts, uv0, img.getWidth(), img.getHeight());
                }
            }

            RenderCaptureUtil.ColorModeResult colorResult =
                RenderCaptureUtil.applyColorMode(ctx, colors, EMPTY_UV);

            String resolvedMaterialKey = ctx.resolveMaterialKey(spriteKey, materialGroupKey);
            ctx.registerSpriteMaterial(spriteKey, resolvedMaterialKey);
            sceneSink.addQuad(resolvedMaterialKey, spriteKey, "voxelbridge:transparent",
                RenderLayer.UNKNOWN, colorResult.tintMode(),
                RENDER_TYPE_RESOLVER.isDoubleSided(renderLayer),
                false,
                positions, uv0, colorResult.uv1(), NORMAL_UP, colors);
        }

        private boolean shouldSkipTextQuad(net.minecraft.client.render.RenderLayer renderLayer) {
            if (renderLayer == null) {
                return false;
            }
            String name = renderLayer.toString().toLowerCase(java.util.Locale.ROOT);
            return name.contains("text_")
                || name.contains("font")
                || name.contains("glyph");
        }

        private void fillUvs(List<RenderCapture.Vertex> verts, float[] uv0, boolean isAtlas, float u0, float u1, float v0, float v1) {
            if (isAtlas) {
                RenderCaptureUtil.fillUvsAtlas(verts, uv0, u0, u1, v0, v1);
            } else {
                RenderCaptureUtil.fillUvsClamp(verts, uv0);
            }
        }

        private void fillUvsPixels(List<RenderCapture.Vertex> verts, float[] uv0, int width, int height) {
            int count = Math.min(4, verts.size());
            float invW = width <= 0 ? 1f : 1f / width;
            float invH = height <= 0 ? 1f : 1f / height;
            for (int i = 0; i < count; i++) {
                RenderCapture.Vertex v = verts.get(i);
                float su = v.u * invW;
                float sv = v.v * invH;
                su = Math.max(0f, Math.min(1f, su));
                sv = Math.max(0f, Math.min(1f, sv));
                uv0[i * 2] = su;
                uv0[i * 2 + 1] = sv;
            }
        }

        private void logTextUvOnce(net.minecraft.client.render.RenderLayer renderLayer, RenderCaptureUtil.UvStats uvStats) {
            if (renderLayer == null || uvStats == null) {
                return;
            }
            String name = renderLayer.toString();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean isText = lower.contains("text_")
                || lower.contains("font")
                || lower.contains("glyph");
            if (!isText) {
                return;
            }
            if (!LOGGED_TEXT_TYPES.add(name)) {
                return;
            }
            VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, String.format(
                "[BlockEntityRenderer] text UV rawU=%s rawV=%s wrappedU=%s wrappedV=%s",
                java.util.Arrays.toString(uvStats.rawU()),
                java.util.Arrays.toString(uvStats.rawV()),
                java.util.Arrays.toString(uvStats.wrappedU()),
                java.util.Arrays.toString(uvStats.wrappedV())
            ));
        }

        private float computeQuadArea(List<RenderCapture.Vertex> verts) {
            if (verts.size() < 3) return 0f;
            RenderCapture.Vertex v0 = verts.get(0);
            RenderCapture.Vertex v1 = verts.get(1);
            RenderCapture.Vertex v2 = verts.get(2);
            float ax = v1.x - v0.x; float ay = v1.y - v0.y; float az = v1.z - v0.z;
            float bx = v2.x - v0.x; float by = v2.y - v0.y; float bz = v2.z - v0.z;
            float cx = ay * bz - az * by;
            float cy = az * bx - ax * bz;
            float cz = ax * by - ay * bx;
            return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
    }
}
