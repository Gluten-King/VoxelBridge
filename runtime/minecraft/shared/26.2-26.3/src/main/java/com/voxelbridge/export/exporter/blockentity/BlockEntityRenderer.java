package com.voxelbridge.export.exporter.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.MaterialGroupKey;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.DefaultAtlasLocator;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextRenderTypeUtil;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.export.exporter.PlaneOffsetTracker;
import com.voxelbridge.export.exporter.capture.CapturedQuadProcessor;
import com.voxelbridge.export.exporter.capture.CapturedTextTextureSupport12111;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import com.voxelbridge.platform.render.capture.CaptureBufferBase;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders BlockEntities and captures their geometry to an IR sink.
 * This replaces the old BerRenderHelper + BerCaptureBuffer system.
 */
public final class BlockEntityRenderer {

    private BlockEntityRenderer() {}

    public static void clearChunkTracker(ExportContext ctx, int chunkX, int chunkZ) {
        chunkPlaneOffsets(ctx).remove(chunkKey(chunkX, chunkZ));
    }

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

    /**
     * Creates a render task that can be executed later (ideally on the main thread).
     */
    public static RenderTask createTask(
        ExportContext ctx,
        BlockEntity blockEntity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides
    ) {
        com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer] Attempting to render BlockEntity: " + blockEntity.getClass().getSimpleName() + " at " + blockEntity.getBlockPos());
        BlockEntityRenderDispatcher dispatcher = ctx.getMc().getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer =
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
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        TextureOverrideMap overrides,
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
        int chunkX,
        int chunkZ
    ) {
        try {
            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer][renderDirect] Starting render for " + blockEntity.getClass().getSimpleName());
            PoseStack poseStack = new PoseStack();
            poseStack.translate(offsetX, offsetY, offsetZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, blockEntity, chunkX, chunkZ, overrides);

            com.voxelbridge.util.debug.VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderer][renderDirect] Calling renderer.render()...");
            // Export-only camera spoof: keep BER distance checks at zero.
            Vec3 camera = Vec3.atCenterOf(blockEntity.getBlockPos());
            Adapters.getBlockEntityRender().render(renderer, blockEntity, 0.0f, poseStack, captureBuffer, 0xF000F0, OverlayTexture.NO_OVERLAY, camera);

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
        }
    }

    /**
     * Task wrapper for batch execution.
     */
    public static final class RenderTask implements Runnable {
        private final ExportContext ctx;
        private final BlockEntity blockEntity;
        private final IrSink sceneSink;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        private final TextureOverrideMap overrides;
        private final net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer;
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
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
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
                this.success = renderDirect(ctx, blockEntity, sceneSink, offsetX, offsetY, offsetZ, overrides, renderer, chunkX, chunkZ);
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
    private static class CaptureBuffer extends CaptureBufferBase {
        private final BlockEntity blockEntity;
        private final TextureOverrideMap overrides;
        private final PlaneOffsetTracker planeOffset;

        CaptureBuffer(ExportContext ctx, IrSink sceneSink, BlockEntity blockEntity, int chunkX, int chunkZ,
                      TextureOverrideMap overrides) {
            super(ctx, sceneSink, (renderType, queuedVertices) -> {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] setNormal called, vertices.size=" + queuedVertices);
                }
            });
            this.blockEntity = blockEntity;
            this.overrides = overrides;
            this.planeOffset = chunkPlaneOffsets(ctx).computeIfAbsent(
                chunkKey(chunkX, chunkZ),
                key -> new PlaneOffsetTracker(3.0f, 1e-3f, 1e-3f, 1000f, 1000f, 1000f)
            );
        }

        void flush() {
            flushCapture();
        }

        TextureOverrideMap overrides() {
            return overrides;
        }

        @Override
        public void onQuad(RenderType renderType, RenderCapture.Submission submission,
                           List<RenderCapture.Vertex> verts) {
            if (verts.size() < 3) return;

            boolean logQuads = VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY);
            if (logQuads) {
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] ========== OUTPUT QUAD START ==========");
                VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] Vertices count: " + verts.size());
            }

            // Build position and color arrays (UV will be filled later after texture resolution)
            float[] positions = new float[12];
            float[] uv0 = new float[8];
            float[] colors = new float[16];
            CapturedQuadProcessor.fillPositionsAndColors(verts, positions, colors);

            // Check for degenerate quad (zero or near-zero area)
            if (verts.size() >= 3) {
                float area = GeometryUtil.computeQuadArea(positions);
                if (area < 0.0001f) {
                    if (logQuads) {
                        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] Skipping degenerate quad (area=" + area + ")");
                    }
                    return;
                }
            }

            recordGeometry();

            RenderCaptureUtil.UvStats uvStats = RenderCaptureUtil.computeUvStats(verts);
            CapturedTextTextureSupport12111.logUvOnce(
                ctx, "blockentity", "BlockEntityRenderer", renderType, uvStats);

            // Generate Material Group Key
            // Format: "blockentity:minecraft:chest"
            String materialGroupKey = MaterialGroupKey.blockEntity(blockEntity);
            if (CapturedTextTextureSupport12111.isTextRenderType(renderType)) {
                materialGroupKey = MaterialSemantic.glyph(materialGroupKey);
            }

            CapturedQuadProcessor.process(
                ctx,
                sceneSink,
                planeOffset,
                renderType,
                submission,
                verts,
                uvStats,
                positions,
                colors,
                uv0,
                blockEntity,
                materialGroupKey,
                this::resolveTexture,
                CapturedTextTextureSupport12111::writeBlockEntityUvs,
                (tracker, quadPositions, faceNormal) -> tracker.applyOffset(quadPositions, faceNormal, approximateDirection(faceNormal)),
                RenderTypeTextureResolver.INSTANCE
            );
        }

        private Direction approximateDirection(float[] normal) {
            return RenderCaptureUtil.approximateDirection(normal);
        }

        private CapturedQuadProcessor.TextureResult resolveTexture(
            ExportContext ctx,
            BlockEntity source,
            RenderType renderType,
            RenderCapture.Submission submission,
            RenderCaptureUtil.UvStats uvStats,
            float[] positions
        ) {
            Identifier rtTexture = renderType != null ? RenderTypeTextureResolver.INSTANCE.resolve(renderType) : null;
            if (rtTexture == null) {
                CapturedTextTextureSupport12111.logMissingTextureOnce(
                    ctx, "blockentity", "BlockEntityRenderer", renderType);
            }
            ResolvedTexture textureRes = ResolvedTexture.fromSprite(submission.sprite());
            if (textureRes == null) {
                textureRes = BlockEntityTextureResolver.INSTANCE.resolve(source, renderType);
            }
            if (textureRes == null && CapturedTextTextureSupport12111.isTextRenderType(renderType) && rtTexture != null) {
                textureRes = new ResolvedTexture(rtTexture, 0f, 1f, 0f, 1f, false, null, null);
            }
            textureRes = CapturedTextTextureSupport12111.resolveFallback(
                ctx, renderType, uvStats, textureRes, "BlockEntityRenderer");
            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(
                    textureRes,
                    atlasLocator(ctx),
                    uvStats,
                    textureRes.atlasLocation()
                );
            }

            TextureOverrideMap overrides = overrides();
            if (textureRes != null && overrides != null) {
                if (overrides.skipQuad(textureRes.texture(), uvStats.rawU(), uvStats.rawV())) {
                    return new CapturedQuadProcessor.TextureResult(
                        null, textureRes, false, 0f, 1f, 0f, 1f, true
                    );
                }
                var mappedHandle = overrides.resolve(textureRes.texture());
                if (mappedHandle != null) {
                    return new CapturedQuadProcessor.TextureResult(
                        mappedHandle.spriteKey(),
                        textureRes,
                        textureRes.isAtlasTexture(),
                        textureRes.u0(),
                        textureRes.u1(),
                        textureRes.v0(),
                        textureRes.v1(),
                        false
                    );
                }
            }

            String spriteKey;
            boolean isAtlasTexture = false;
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;

            if (textureRes != null && textureRes.texture() != null) {
                spriteKey = com.voxelbridge.export.texture.BlockEntityTextureManager.registerTexture(ctx, textureRes);
                isAtlasTexture = textureRes.isAtlasTexture();
                u0 = textureRes.u0(); u1 = textureRes.u1();
                v0 = textureRes.v0(); v1 = textureRes.v1();
            } else {
                spriteKey = "blockentity:minecraft/block/white";
            }

            return new CapturedQuadProcessor.TextureResult(
                spriteKey, textureRes, isAtlasTexture, u0, u1, v0, v1, false
            );
        }

    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static ConcurrentHashMap<Long, PlaneOffsetTracker> chunkPlaneOffsets(ExportContext ctx) {
        return ctx.session().computeAttribute("blockentity:plane-offsets", ConcurrentHashMap::new);
    }

    private static AtlasLocator atlasLocator(ExportContext ctx) {
        return ctx.session().computeAttribute(
            "block-entity-renderer:atlas-locator",
            () -> new DefaultAtlasLocator(ClientAccessHolder.get()));
    }
}
