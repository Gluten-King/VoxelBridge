package com.voxelbridge.export.exporter.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.ir.IrSink;
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

    private static AtlasLocator ATLAS_LOCATOR = new DefaultAtlasLocator(ClientAccessHolder.get());
    private static final ThreadLocal<TextureOverrideMap> OVERRIDES = new ThreadLocal<>();
    private static TextureResolver<BlockEntity> TEXTURE_RESOLVER = BlockEntityTextureResolver.INSTANCE;
    private static RenderTypeResolver RENDER_TYPE_RESOLVER = RenderTypeTextureResolver.INSTANCE;
    private static final ConcurrentHashMap<Long, PlaneOffsetTracker> CHUNK_PLANE_OFFSETS = new ConcurrentHashMap<>();

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

    public static void clearChunkTracker(int chunkX, int chunkZ) {
        CHUNK_PLANE_OFFSETS.remove(chunkKey(chunkX, chunkZ));
    }

    /** Clears all per-session caches. Call at export end to release memory. */
    public static void clearSessionCaches() {
        CHUNK_PLANE_OFFSETS.clear();
        CaptureBuffer.TEXTURE_IMAGE_CACHE.clear();
        CaptureBuffer.LOGGED_TEXT_TYPES.clear();
        CaptureBuffer.LOGGED_TEXT_MISSING_TEXTURE.clear();
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
            if (overrides != null) {
                OVERRIDES.set(overrides);
            }
            PoseStack poseStack = new PoseStack();
            poseStack.translate(offsetX, offsetY, offsetZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, blockEntity, chunkX, chunkZ);

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
        private static final Set<String> LOGGED_TEXT_TYPES = ConcurrentHashMap.newKeySet();
        private static final Set<String> LOGGED_TEXT_MISSING_TEXTURE = ConcurrentHashMap.newKeySet();
        private static final ConcurrentHashMap<String, BufferedImage> TEXTURE_IMAGE_CACHE = new ConcurrentHashMap<>();
        private final BlockEntity blockEntity;
        private final TextureOverrideMap overrides;
        private final PlaneOffsetTracker planeOffset;

        CaptureBuffer(ExportContext ctx, IrSink sceneSink, BlockEntity blockEntity, int chunkX, int chunkZ) {
            super(ctx, sceneSink, (renderType, queuedVertices) -> {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.BLOCKENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[VertexCollector] setNormal called, vertices.size=" + queuedVertices);
                }
            });
            this.blockEntity = blockEntity;
            this.overrides = OVERRIDES.get();
            this.planeOffset = CHUNK_PLANE_OFFSETS.computeIfAbsent(
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
        public void onQuad(RenderType renderType, List<RenderCapture.Vertex> verts) {
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
            logTextUvOnce(renderType, uvStats);

            // Generate Material Group Key
            // Format: "blockentity:minecraft:chest"
            String materialGroupKey = MaterialGroupKey.blockEntity(blockEntity);

            CapturedQuadProcessor.process(
                ctx,
                sceneSink,
                planeOffset,
                renderType,
                verts,
                uvStats,
                positions,
                colors,
                uv0,
                blockEntity,
                materialGroupKey,
                this::resolveTexture,
                this::writeUvs,
                (tracker, quadPositions, faceNormal) -> tracker.applyOffset(quadPositions, faceNormal, approximateDirection(faceNormal)),
                RENDER_TYPE_RESOLVER
            );
        }

        private Direction approximateDirection(float[] normal) {
            return RenderCaptureUtil.approximateDirection(normal);
        }

        private CapturedQuadProcessor.TextureResult resolveTexture(
            ExportContext ctx,
            BlockEntity source,
            RenderType renderType,
            RenderCaptureUtil.UvStats uvStats,
            float[] positions
        ) {
            Identifier rtTexture = renderType != null ? RENDER_TYPE_RESOLVER.resolve(renderType) : null;
            if (rtTexture == null) {
                logTextRenderTypeMissingTexture(renderType);
            }
            ResolvedTexture textureRes = TEXTURE_RESOLVER.resolve(source, renderType);
            if (textureRes == null && isTextRenderType(renderType) && rtTexture != null) {
                textureRes = new ResolvedTexture(rtTexture, 0f, 1f, 0f, 1f, false, null, null);
            }
            textureRes = resolveTextFallbackTexture(ctx, renderType, uvStats, textureRes);
            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(
                    textureRes,
                    ATLAS_LOCATOR,
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

        private ResolvedTexture resolveTextFallbackTexture(ExportContext ctx,
                                                           RenderType renderType,
                                                           RenderCaptureUtil.UvStats uvStats,
                                                           ResolvedTexture textureRes) {
            if (!isTextRenderType(renderType)) {
                return textureRes;
            }
            Identifier current = textureRes != null ? textureRes.texture() : null;
            if (!isDefaultOrMissingLike(current)) {
                return textureRes;
            }
            Identifier selected = extractFontTextureFromRenderType(renderType);
            if (selected == null) {
                selected = pickBestFontPage(ctx, uvStats);
            }
            if (selected == null) {
                return textureRes;
            }
            VoxelBridgeLogger.info(LogModule.DYNAMIC_MAP,
                "[BlockEntityRenderer] Text fallback texture mapped " + current + " -> " + selected);
            return new ResolvedTexture(selected, 0f, 1f, 0f, 1f, false, null, null);
        }

        private Identifier pickBestFontPage(ExportContext ctx, RenderCaptureUtil.UvStats uvStats) {
            return null;
        }

        private BufferedImage loadTextureImage(ExportContext ctx, ResolvedTexture textureRes) {
            if (textureRes == null || textureRes.texture() == null) {
                return null;
            }
            String key = textureRes.texture().toString();
            BufferedImage cached = TEXTURE_IMAGE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            BufferedImage loaded = ctx.getTextureAccess().readTexture(key, true);
            if (loaded != null) {
                TEXTURE_IMAGE_CACHE.put(key, loaded);
            }
            return loaded;
        }

        private boolean isDefaultOrMissingLike(Identifier loc) {
            return TextRenderTypeUtil.isDefaultOrMissingLike(loc);
        }

        private boolean isTextRenderType(RenderType renderType) {
            return TextRenderTypeUtil.isTextRenderType(renderType);
        }

        private Identifier extractFontTextureFromRenderType(RenderType renderType) {
            return TextRenderTypeUtil.extractFontTexture(renderType);
        }

        private void writeUvs(ExportContext ctx,
                              List<RenderCapture.Vertex> verts,
                              RenderCaptureUtil.UvStats uvStats,
                              boolean useAtlasUv,
                              float u0,
                              float u1,
                              float v0,
                              float v1,
                              String spriteKey,
                              ResolvedTexture textureRes,
                              float[] uv0) {
            if (useAtlasUv) {
                RenderCaptureUtil.fillUvsAtlas(verts, uv0, u0, u1, v0, v1);
            } else {
                RenderCaptureUtil.fillUvsClamp(verts, uv0);
            }

            if (!useAtlasUv && uvStats != null && textureRes != null && (uvStats.maxU() > 1f || uvStats.maxV() > 1f)) {
                BufferedImage img = ctx.getCachedSpriteImage(spriteKey);
                if (img == null) {
                    img = loadTextureImage(ctx, textureRes);
                }
                if (img != null) {
                    RenderCaptureUtil.fillUvsPixels(verts, uv0, img.getWidth(), img.getHeight());
                }
            }
        }

        private void logTextUvOnce(RenderType renderType, RenderCaptureUtil.UvStats uvStats) {
            if (renderType == null || uvStats == null) {
                return;
            }
            String name = renderType.toString();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean isText = lower.contains("text_")
                || lower.contains("neoforge_text")
                || lower.contains("font")
                || lower.contains("glyph");
            if (!isText) {
                return;
            }
            if (!LOGGED_TEXT_TYPES.add(name)) {
                return;
            }
            VoxelBridgeLogger.info(LogModule.DYNAMIC_MAP, String.format(
                "[BlockEntityRenderer] text UV rawU=%s rawV=%s wrappedU=%s wrappedV=%s",
                java.util.Arrays.toString(uvStats.rawU()),
                java.util.Arrays.toString(uvStats.rawV()),
                java.util.Arrays.toString(uvStats.wrappedU()),
                java.util.Arrays.toString(uvStats.wrappedV())
            ));
        }

        private void logTextRenderTypeMissingTexture(RenderType renderType) {
            if (!isTextRenderType(renderType)) {
                return;
            }
            String key = String.valueOf(renderType);
            if (!LOGGED_TEXT_MISSING_TEXTURE.add(key)) {
                return;
            }
            VoxelBridgeLogger.warn(LogModule.DYNAMIC_MAP,
                "[BlockEntityRenderer] text RenderType texture unresolved: " + key);
        }

    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
