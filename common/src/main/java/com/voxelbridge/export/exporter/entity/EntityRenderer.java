package com.voxelbridge.export.exporter.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.MaterialGroupKey;
import com.voxelbridge.export.exporter.PlaneOffsetTracker;
import com.voxelbridge.export.exporter.capture.CapturedQuadProcessor;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.DefaultAtlasLocator;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextRenderTypeUtil;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.compat.AtlasCompat;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import com.voxelbridge.platform.render.capture.CaptureBufferBase;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.platform.texture.TextureLoader;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures entity renderer output into an IR sink.
 */
public final class EntityRenderer {

    private static AtlasLocator ATLAS_LOCATOR = new DefaultAtlasLocator(ClientAccessHolder.get());
    private static volatile TextureResolver<Entity> OVERRIDE_RESOLVER;
    private static RenderTypeResolver RENDER_TYPE_RESOLVER = RenderTypeTextureResolver.INSTANCE;
    private static final ConcurrentHashMap<Long, PlaneOffsetTracker> CHUNK_PLANE_OFFSETS = new ConcurrentHashMap<>();
    private static final Object MAP_DECOR_ATLAS_LOCK = new Object();
    private static volatile int[] MAP_DECOR_ATLAS_SIZE; // [width, height], cached after first resolve

    private EntityRenderer() {}

    public static void setAtlasLocator(AtlasLocator locator) {
        if (locator != null) {
            ATLAS_LOCATOR = locator;
        }
    }

    public static void setTextureResolver(TextureResolver<Entity> resolver) {
        OVERRIDE_RESOLVER = resolver;
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
        MAP_DECOR_ATLAS_SIZE = null;
        CaptureBuffer.TEXTURE_IMAGE_CACHE.clear();
        CaptureBuffer.LOGGED_TEXT_TYPES.clear();
        CaptureBuffer.LOGGED_TEXT_MISSING_TEXTURE.clear();
    }

    public static boolean render(
        ExportContext ctx,
        Entity entity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        return renderInternal(ctx, entity, sceneSink, offsetX, offsetY, offsetZ, true);
    }

    public static boolean renderOnMainThread(
        ExportContext ctx,
        Entity entity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        return renderInternal(ctx, entity, sceneSink, offsetX, offsetY, offsetZ, false);
    }

    private static boolean renderInternal(
        ExportContext ctx,
        Entity entity,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        boolean scheduleOnMainThread
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
            net.minecraft.client.renderer.entity.EntityRenderer renderer =
                    dispatcher.getRenderer(entity);
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
            if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hangingEntity
                && Adapters.getEntityRender().shouldApplyHangingOffset()) {
                net.minecraft.world.phys.Vec3 base = Adapters.getEntityRender().getHangingOffsetBase(hangingEntity);
                if (base != null) {
                    finalX = base.x + offsetX;
                    finalY = base.y + offsetY;
                    finalZ = base.z + offsetZ;
                }
                double[] hangingOffset = HangingEntityPositionUtil.calculateRenderOffset(hangingEntity);
                finalX += hangingOffset[0];
                finalY += hangingOffset[1];
                finalZ += hangingOffset[2];

                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[HangingEntity] Applied direction offset: direction=%s offset=[%.4f, %.4f, %.4f]",
                    hangingEntity.getDirection(), hangingOffset[0], hangingOffset[1], hangingOffset[2]));
            }

            poseStack.translate(finalX, finalY, finalZ);

            double deltaX = finalX - entity.getX();
            double deltaY = finalY - entity.getY();
            double deltaZ = finalZ - entity.getZ();
            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, entity, deltaX, deltaY, deltaZ);
            float partial = 0f;
            float yaw = entity.getYRot();
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Rotation] %s yaw=%.2fdeg (actual=%.2fdeg, isHanging=%s)",
                    entity.getType(),
                    yaw,
                    entity.getYRot(),
                    entity instanceof net.minecraft.world.entity.decoration.HangingEntity));
            }

            // Use max light level for better visibility
            int packedLight = 0xF000F0;

            Exception[] renderException = new Exception[1];

            Runnable renderCall = () -> {
                try {
                    Object renderState = Adapters.getEntityRender().createRenderState(renderer, entity, yaw, partial);
                    net.minecraft.world.phys.Vec3 renderOffset =
                        Adapters.getEntityRender().getRenderOffset(renderer, entity, partial, renderState);
                    if (renderOffset != null) {
                        poseStack.translate(renderOffset.x(), renderOffset.y(), renderOffset.z());
                        captureBuffer.setRenderOffset(renderOffset);
                    } else {
                        captureBuffer.setRenderOffset(null);
                    }
                    Adapters.getEntityRender().render(renderer, renderState, entity, yaw, partial, poseStack, captureBuffer, packedLight);
                } catch (Exception e) {
                    renderException[0] = e;
                }
            };

            if (scheduleOnMainThread) {
                ctx.runOnMainThread(renderCall);
            } else {
                renderCall.run();
            }

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

    /**
     * Capture buffer for entity renders.
     */
    private static class CaptureBuffer extends CaptureBufferBase {
        private static final Set<String> LOGGED_TEXT_TYPES = ConcurrentHashMap.newKeySet();
        private static final Set<String> LOGGED_TEXT_MISSING_TEXTURE = ConcurrentHashMap.newKeySet();
        private static final ConcurrentHashMap<String, BufferedImage> TEXTURE_IMAGE_CACHE = new ConcurrentHashMap<>();
        private final double offsetX, offsetY, offsetZ;
        private final Entity entity;
        private final PlaneOffsetTracker planeOffset;
        private final double baseDeltaX;
        private final double baseDeltaY;
        private final double baseDeltaZ;
        private net.minecraft.world.phys.Vec3 renderOffset;
        private long bucketKey;
        private boolean bucketKeyReady;
        private int quadCount = 0;
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        CaptureBuffer(ExportContext ctx, IrSink sceneSink, double offsetX, double offsetY, double offsetZ, Entity entity,
                      double baseDeltaX, double baseDeltaY, double baseDeltaZ) {
            super(ctx, sceneSink, null);
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.entity = entity;
            this.baseDeltaX = baseDeltaX;
            this.baseDeltaY = baseDeltaY;
            this.baseDeltaZ = baseDeltaZ;
            int chunkX = entity.blockPosition().getX() >> 4;
            int chunkZ = entity.blockPosition().getZ() >> 4;
            this.planeOffset = CHUNK_PLANE_OFFSETS.computeIfAbsent(
                chunkKey(chunkX, chunkZ),
                key -> new PlaneOffsetTracker(3.0f, 1e-3f, 1e-3f, 1000f, 1000f, 1000f)
            );
        }

        void flush() {
            flushCapture();
            if (hadGeometry()) {
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

        @Override
        public void onQuad(RenderType renderType, List<RenderCapture.Vertex> verts) {
            if (verts.size() < 3) return;

            recordGeometry();
            quadCount++;

            float[] positions = new float[12];
            float[] uv0 = new float[8];
            float[] colors = new float[16];

            CapturedQuadProcessor.fillPositionsAndColors(verts, positions, colors);
            for (int i = 0; i < Math.min(4, verts.size()); i++) {
                float x = positions[i * 3];
                float y = positions[i * 3 + 1];
                float z = positions[i * 3 + 2];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }

            if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Quad#%d] Vertices: v0=[%.3f,%.3f,%.3f] v1=[%.3f,%.3f,%.3f]",
                    quadCount,
                    positions[0], positions[1], positions[2],
                    positions[3], positions[4], positions[5]));
            }

            if (VoxelBridgeLogger.isTraceEnabled(LogModule.ENTITY)) {
                VoxelBridgeLogger.trace(LogModule.ENTITY, String.format(
                    "[RenderType] %s renderType=%s",
                    entity.getType(),
                    renderType != null ? renderType.toString() : "null"));
            }
            RenderCaptureUtil.UvStats uvStats = RenderCaptureUtil.computeUvStats(verts);
            logTextUvOnce(renderType, uvStats);
            uvStats = normalizeMapDecorUvStats(uvStats, renderType);
            String materialGroupKey = MaterialGroupKey.entity(entity);
            if (isTextRenderType(renderType)) {
                materialGroupKey = MaterialSemantic.glyph(materialGroupKey);
            }
            updateBucketKey();
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
                entity,
                materialGroupKey,
                this::resolveTexture,
                this::writeUvs,
                (tracker, quadPositions, faceNormal) ->
                    tracker.applyOffsetWithBucketKey(quadPositions, faceNormal, approximateDirection(faceNormal), bucketKey),
                RENDER_TYPE_RESOLVER
            );
        }

        void setRenderOffset(net.minecraft.world.phys.Vec3 renderOffset) {
            this.renderOffset = renderOffset;
            this.bucketKeyReady = false;
        }

        private void updateBucketKey() {
            if (bucketKeyReady) {
                return;
            }
            AABB bounds = entity.getBoundingBox();
            double dx = baseDeltaX;
            double dy = baseDeltaY;
            double dz = baseDeltaZ;
            if (renderOffset != null) {
                dx += renderOffset.x();
                dy += renderOffset.y();
                dz += renderOffset.z();
            }
            bounds = bounds.move(dx, dy, dz);
            bucketKey = PlaneOffsetTracker.hashAabb(
                (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ
            );
            bucketKeyReady = true;
        }

        private CapturedQuadProcessor.TextureResult resolveTexture(
            ExportContext ctx,
            Entity source,
            RenderType renderType,
            RenderCaptureUtil.UvStats uvStats,
            float[] positions
        ) {
            ResourceLocation rtTexture = renderType != null ? RENDER_TYPE_RESOLVER.resolve(renderType) : null;
            if (rtTexture == null) {
                logTextRenderTypeMissingTexture(renderType);
            }
            if (rtTexture != null && rtTexture.getPath() != null && rtTexture.getPath().contains("map_decorations")) {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
                    VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                        "[MapDecor] Skipping map decoration quad for " + source.getType()
                            + " renderType=" + renderType + " texture=" + rtTexture);
                }
                return new CapturedQuadProcessor.TextureResult(null, null, false, 0f, 0f, 0f, 0f, true);
            }
            TextureResolver<Entity> resolver = resolveTextureResolver();
            ResolvedTexture textureRes = resolver != null ? resolver.resolve(source, renderType) : null;
            if (textureRes == null && isTextRenderType(renderType) && rtTexture != null) {
                textureRes = new ResolvedTexture(rtTexture, 0f, 1f, 0f, 1f, false, null, null);
            }
            textureRes = resolveTextFallbackTexture(ctx, renderType, uvStats, textureRes);

            RenderCaptureUtil.UvStats atlasUvStats = uvStats;
            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(textureRes, ATLAS_LOCATOR, atlasUvStats, textureRes.atlasLocation());
            }

            if (source instanceof net.minecraft.world.entity.decoration.Painting painting
                && textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() != null) {
                textureRes = selectPaintingTexture(painting, textureRes, positions);
            }

            String spriteKey;
            boolean isAtlasTexture = false;
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;

            if (textureRes != null && textureRes.texture() != null) {
                EntityTextureManager.TextureHandle handle = null;
                if (source instanceof AbstractClientPlayer player) {
                    handle = tryRegisterPlayerAttachmentTexture(ctx, player, textureRes.texture());
                }
                if (handle == null) {
                    handle = EntityTextureManager.register(ctx, textureRes.texture().toString());
                }
                spriteKey = handle.spriteKey();
                isAtlasTexture = textureRes.isAtlasTexture();
                u0 = textureRes.u0(); u1 = textureRes.u1();
                v0 = textureRes.v0(); v1 = textureRes.v1();

                if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[Texture] %s texture=%s isAtlas=%s",
                        source.getType(),
                        textureRes.texture() != null ? textureRes.texture() : "null",
                        isAtlasTexture));
                }
                if (VoxelBridgeLogger.isTraceEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.trace(LogModule.ENTITY, String.format(
                        "[UV] %s u=[%.4f, %.4f] v=[%.4f, %.4f]",
                        source.getType(), u0, u1, v0, v1));
                }

                // Cache atlas sprite pixels for export.
                if (isAtlasTexture && textureRes.sprite() != null) {
                    BufferedImage spriteImg = ctx.getTextureAccess().readSprite(textureRes.sprite());
                    if (spriteImg != null) {
                        ctx.cacheSpriteImage(spriteKey, spriteImg);
                    }
                }
            } else {
                spriteKey = "entity:minecraft/white";
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[Quad#" + quadCount + "] No texture resolved, using white fallback");
            }

            return new CapturedQuadProcessor.TextureResult(
                spriteKey,
                textureRes,
                isAtlasTexture,
                u0,
                u1,
                v0,
                v1,
                false
            );
        }

        private ResolvedTexture resolveTextFallbackTexture(ExportContext ctx,
                                                           RenderType renderType,
                                                           RenderCaptureUtil.UvStats uvStats,
                                                           ResolvedTexture textureRes) {
            if (!isTextRenderType(renderType)) {
                return textureRes;
            }
            ResourceLocation current = textureRes != null ? textureRes.texture() : null;
            if (!isDefaultOrMissingLike(current)) {
                return textureRes;
            }
            ResourceLocation selected = extractFontTextureFromRenderType(renderType);
            if (selected == null) {
                selected = pickBestFontPage(ctx, uvStats);
            }
            if (selected == null) {
                return textureRes;
            }
            VoxelBridgeLogger.info(LogModule.DYNAMIC_MAP,
                "[EntityRenderer] Text fallback texture mapped " + current + " -> " + selected);
            return new ResolvedTexture(selected, 0f, 1f, 0f, 1f, false, null, null);
        }

        private ResourceLocation pickBestFontPage(ExportContext ctx, RenderCaptureUtil.UvStats uvStats) {
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

        private boolean isDefaultOrMissingLike(ResourceLocation loc) {
            return TextRenderTypeUtil.isDefaultOrMissingLike(loc);
        }

        private boolean isTextRenderType(RenderType renderType) {
            return TextRenderTypeUtil.isTextRenderType(renderType);
        }

        private ResourceLocation extractFontTextureFromRenderType(RenderType renderType) {
            return TextRenderTypeUtil.extractFontTexture(renderType);
        }

        private boolean isFontTexture(ResourceLocation loc) {
            if (loc == null || loc.getPath() == null) {
                return false;
            }
            String path = loc.getPath().toLowerCase(Locale.ROOT);
            return path.contains("/font/") || path.startsWith("font/");
        }

        private void logTextUvOnce(RenderType renderType, RenderCaptureUtil.UvStats uvStats) {
            if (renderType == null || uvStats == null) {
                return;
            }
            String name = renderType.toString();
            String lower = name.toLowerCase(Locale.ROOT);
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
                "[EntityRenderer] text UV rawU=%s rawV=%s wrappedU=%s wrappedV=%s",
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
                "[EntityRenderer] text RenderType texture unresolved: " + key);
        }

        private TextureResolver<Entity> resolveTextureResolver() {
            TextureResolver<Entity> override = OVERRIDE_RESOLVER;
            if (override != null) {
                return override;
            }
            TextureResolver<Entity> adapterResolver = Adapters.getEntityRender().getTextureResolver();
            return adapterResolver != null ? adapterResolver : EntityTextureResolver.INSTANCE;
        }

        private Direction approximateDirection(float[] normal) {
            return RenderCaptureUtil.approximateDirection(normal);
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
                // Atlas texture: normalize UV within sprite bounds
                boolean usedPixels = false;
                if (!usedPixels) {
                    RenderCaptureUtil.fillUvsAtlas(verts, uv0, u0, u1, v0, v1);
                }
            } else {
                boolean isFontTex = isFontTexture(textureRes != null ? textureRes.texture() : null);
                if (isFontTex && uvStats != null && (uvStats.maxU() > 1f || uvStats.maxV() > 1f)) {
                    BufferedImage img = loadTextureImage(ctx, textureRes);
                    if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                        RenderCaptureUtil.fillUvsPixels(verts, uv0, img.getWidth(), img.getHeight());
                        return;
                    }
                }
                // Non-atlas texture: find actual UV bounds and normalize
                // This is important for entities like paintings where UV may not be in [0,1]
                RenderCaptureUtil.UvFillResult result = RenderCaptureUtil.fillUvsNormalize(verts, uv0, uvStats);
                if (result.mode() == RenderCaptureUtil.UvFillMode.NORMALIZED) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[UV Normalization] Painting/Entity UV remapped: U[%.3f, %.3f] V[%.3f, %.3f] -> [0,1]x[0,1]",
                        result.minU(), result.maxU(), result.minV(), result.maxV()));
                } else if (result.mode() == RenderCaptureUtil.UvFillMode.DEGENERATE) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, "[UV Normalization] Degenerate UV detected, using [0,0] for all vertices");
                }
            }
        }

        private ResolvedTexture selectPaintingTexture(
            net.minecraft.world.entity.decoration.Painting painting,
            ResolvedTexture current,
            float[] positions
        ) {
            ResolvedTexture normalized = normalizePaintingTexture(current);
            if (isPaintingFrontQuad(painting, positions)) {
                return normalized;
            }

            TextureAtlasSprite backSprite = ClientAccessHolder.get().getPaintingTextures().getBackSprite();
            if (backSprite == null || isMissingSprite(backSprite)) {
                return normalized;
            }

            ResourceLocation spriteName = backSprite.contents() != null ? backSprite.contents().name() : normalized.texture();
            spriteName = normalizePaintingSpriteName(spriteName);
            ResourceLocation atlas = backSprite.atlasLocation();
            return new ResolvedTexture(spriteName, backSprite.getU0(), backSprite.getU1(),
                backSprite.getV0(), backSprite.getV1(), true, backSprite, atlas);
        }

        private ResolvedTexture normalizePaintingTexture(ResolvedTexture current) {
            if (current == null || current.texture() == null) {
                return current;
            }
            ResourceLocation normalized = normalizePaintingSpriteName(current.texture());
            if (normalized.equals(current.texture())) {
                return current;
            }
            return new ResolvedTexture(normalized, current.u0(), current.u1(),
                current.v0(), current.v1(), current.isAtlasTexture(), current.sprite(), current.atlasLocation());
        }

        private boolean isPaintingFrontQuad(net.minecraft.world.entity.decoration.Painting painting, float[] positions) {
            Direction direction = painting.getDirection();
            if (direction == null) {
                return true;
            }

            int axisIndex;
            double frontCoord;
            var bounds = painting.getBoundingBox().move(offsetX, offsetY, offsetZ);
            switch (direction) {
                case NORTH -> {
                    axisIndex = 2;
                    frontCoord = bounds.minZ;
                }
                case SOUTH -> {
                    axisIndex = 2;
                    frontCoord = bounds.maxZ;
                }
                case WEST -> {
                    axisIndex = 0;
                    frontCoord = bounds.minX;
                }
                case EAST -> {
                    axisIndex = 0;
                    frontCoord = bounds.maxX;
                }
                default -> {
                    return true;
                }
            }

            float center = 0f;
            for (int i = 0; i < 4; i++) {
                center += positions[i * 3 + axisIndex];
            }
            center /= 4f;
            return Math.abs(center - frontCoord) <= 1e-3f;
        }

        private ResourceLocation normalizePaintingSpriteName(ResourceLocation spriteName) {
            if (spriteName == null) {
                return null;
            }
            String path = spriteName.getPath();
            if (path.startsWith("textures/painting/") || path.startsWith("painting/")) {
                return spriteName;
            }
            return ResourceLocation.fromNamespaceAndPath(spriteName.getNamespace(), "painting/" + path);
        }

        private boolean isMissingSprite(TextureAtlasSprite sprite) {
            return sprite.contents().name().toString().contains("missingno");
        }

    }

    private static RenderCaptureUtil.UvStats normalizeMapDecorUvStats(RenderCaptureUtil.UvStats uvStats, RenderType renderType) {
        if (uvStats == null || renderType == null) {
            return uvStats;
        }
        ResourceLocation texture = RENDER_TYPE_RESOLVER.resolve(renderType);
        if (texture == null || texture.getPath() == null || !texture.getPath().contains("map_decorations")) {
            return uvStats;
        }
        boolean looksPixelUv =
            uvStats.maxU() > 1.1f || uvStats.maxV() > 1.1f ||
            uvStats.minU() < -0.1f || uvStats.minV() < -0.1f;
        if (!looksPixelUv) {
            return uvStats;
        }
        int[] size = getMapDecorAtlasSize(texture);
        if (size == null) {
            return uvStats;
        }
        RenderCaptureUtil.UvStats normalized = RenderCaptureUtil.normalizeUvStatsPixels(uvStats, size[0], size[1]);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
            VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                "[MapDecor] Normalized UVs using atlas size "
                    + size[0] + "x" + size[1]
                    + " atlas=" + texture);
        }
        return normalized != null ? normalized : uvStats;
    }

    private static int[] getMapDecorAtlasSize(ResourceLocation atlasLoc) {
        int[] cached = MAP_DECOR_ATLAS_SIZE;
        if (cached != null) {
            return cached;
        }
        synchronized (MAP_DECOR_ATLAS_LOCK) {
            cached = MAP_DECOR_ATLAS_SIZE;
            if (cached != null) {
                return cached;
            }
            try {
                var tex = ClientAccessHolder.get().getTextureManager().getTexture(atlasLoc);
                if (!(tex instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) {
                    if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
                        VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                            "[MapDecor] Atlas texture not available in TextureManager for " + atlasLoc
                                + " (got " + (tex != null ? tex.getClass().getName() : "null") + ")");
                    }
                    return null;
                }
                int widthSum = 0;
                int heightSum = 0;
                int count = 0;
                for (TextureAtlasSprite sprite : AtlasCompat.getAllSprites(atlas)) {
                    if (sprite == null || sprite.contents() == null) {
                        continue;
                    }
                    int sw = sprite.contents().width();
                    int sh = sprite.contents().height();
                    float du = sprite.getU1() - sprite.getU0();
                    float dv = sprite.getV1() - sprite.getV0();
                    if (sw <= 0 || sh <= 0 || du <= 1e-6f || dv <= 1e-6f) {
                        continue;
                    }
                    int aw = Math.round(sw / du);
                    int ah = Math.round(sh / dv);
                    if (aw <= 0 || ah <= 0) {
                        continue;
                    }
                    widthSum += aw;
                    heightSum += ah;
                    count++;
                    if (count >= 8) {
                        break;
                    }
                }
                if (count == 0) {
                    return null;
                }
                int w = Math.round(widthSum / (float) count);
                int h = Math.round(heightSum / (float) count);
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
                    VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                        "[MapDecor] Estimated atlas size from sprites: "
                            + w + "x" + h + " atlas=" + atlasLoc);
                }
                MAP_DECOR_ATLAS_SIZE = new int[] { w, h };
                return MAP_DECOR_ATLAS_SIZE;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static EntityTextureManager.TextureHandle tryRegisterPlayerAttachmentTexture(
        ExportContext ctx,
        AbstractClientPlayer player,
        ResourceLocation texture
    ) {
        String type = detectPlayerAttachmentType(texture);
        if (type == null) {
            return null;
        }
        BufferedImage image = readTextureWithFallback(texture);
        if (image == null) {
            return null;
        }
        String playerName = sanitizePlayerName(player.getGameProfile().getName());
        String key = "entity:player/" + type + "/" + playerName;
        String relativePath = "textures/entity_textures/player/" + playerName + "_" + type + ".png";
        return EntityTextureManager.registerGenerated(ctx, key, relativePath, image);
    }

    private static String detectPlayerAttachmentType(ResourceLocation texture) {
        if (texture == null) {
            return null;
        }
        String path = texture.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("elytra")) {
            return "elytra";
        }
        if (path.contains("cape") || path.contains("cloak")) {
            return "cape";
        }
        return null;
    }

    private static BufferedImage readTextureWithFallback(ResourceLocation texture) {
        BufferedImage image = TextureLoader.readTexture(texture, ExportRuntimeConfig.isAnimationEnabled());
        if (image != null) {
            return image;
        }
        ResourceLocation fallback = resolveTexturePathFallback(texture);
        if (!fallback.equals(texture)) {
            return TextureLoader.readTexture(fallback, ExportRuntimeConfig.isAnimationEnabled());
        }
        return null;
    }

    private static ResourceLocation resolveTexturePathFallback(ResourceLocation texture) {
        String path = texture.getPath();
        if (path.startsWith("skins/") || path.startsWith("skin/")) {
            return texture;
        }
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), path);
    }

    private static String sanitizePlayerName(String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '-' || c == '_';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}

