package com.voxelbridge.export.exporter.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.MaterialGroupKey;
import com.voxelbridge.export.exporter.PlaneOffsetTracker;
import com.voxelbridge.export.exporter.capture.CapturedQuadProcessor;
import com.voxelbridge.export.exporter.capture.CapturedTextTextureSupport;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.DefaultAtlasLocator;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextRenderTypeUtil;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.texture.TexturePathResolver;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures entity renderer output into an IR sink.
 */
public final class EntityRenderer {

    private EntityRenderer() {}

    public static void clearChunkTracker(ExportContext ctx, int chunkX, int chunkZ) {
        chunkPlaneOffsets(ctx).remove(chunkKey(chunkX, chunkZ));
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
            this.planeOffset = chunkPlaneOffsets(ctx).computeIfAbsent(
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
            CapturedTextTextureSupport.logUvOnce(
                ctx, "entity", "EntityRenderer/1.20.1", renderType, uvStats);
            String materialGroupKey = MaterialGroupKey.entity(entity);
            if (CapturedTextTextureSupport.isTextRenderType(renderType)) {
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
                CapturedTextTextureSupport::writeEntityUvs,
                (tracker, quadPositions, faceNormal) ->
                    tracker.applyOffsetWithBucketKey(quadPositions, faceNormal, approximateDirection(faceNormal), bucketKey),
                RenderTypeTextureResolver.INSTANCE
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
            ResourceLocation rtTexture = renderType != null ? RenderTypeTextureResolver.INSTANCE.resolve(renderType) : null;
            TextureResolver<Entity> resolver = resolveTextureResolver();
            ResolvedTexture textureRes = resolver != null ? resolver.resolve(source, renderType) : null;
            if (textureRes == null && CapturedTextTextureSupport.isTextRenderType(renderType) && rtTexture != null) {
                textureRes = new ResolvedTexture(rtTexture, 0f, 1f, 0f, 1f, false, null, null);
            }
            textureRes = CapturedTextTextureSupport.resolveFallback(
                ctx, renderType, uvStats, textureRes, "EntityRenderer/1.20.1");

            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(textureRes, atlasLocator(ctx), uvStats, textureRes.atlasLocation());
            }

            if (source instanceof net.minecraft.world.entity.decoration.Painting painting
                && textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() != null) {
                textureRes = PaintingTextureStrategy.select(
                    painting, textureRes, positions, offsetX, offsetY, offsetZ);
            }

            String spriteKey;
            boolean isAtlasTexture = false;
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;

            if (textureRes != null && textureRes.texture() != null) {
                EntityTextureManager.TextureHandle handle = null;
                if (source instanceof AbstractClientPlayer player) {
                    handle = PlayerAttachmentTextureStrategy.register(ctx, player, textureRes.texture());
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
                    BufferedImage spriteImg = ctx.readSprite(textureRes.sprite());
                    if (spriteImg != null) {
                        ctx.cacheSpriteImage(spriteKey, spriteImg);
                    }
                }
            } else {
                spriteKey = ensureWhiteEntityFallback(ctx);
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

        private TextureResolver<Entity> resolveTextureResolver() {
            TextureResolver<Entity> adapterResolver = Adapters.getEntityRender().getTextureResolver();
            return adapterResolver != null ? adapterResolver : EntityTextureResolver.INSTANCE;
        }

        private Direction approximateDirection(float[] normal) {
            int axis = GeometryUtil.dominantAxisSigned(normal);
            return switch (axis) {
                case 1 -> Direction.EAST;
                case -1 -> Direction.WEST;
                case 2 -> Direction.UP;
                case -2 -> Direction.DOWN;
                case 3 -> Direction.SOUTH;
                case -3 -> Direction.NORTH;
                default -> null;
            };
        }

    }

    private static String ensureWhiteEntityFallback(ExportContext ctx) {
        final String spriteKey = "entity:minecraft/white";
        if (ctx.getMaterialPaths().containsKey(spriteKey)) {
            return spriteKey;
        }
        final int size = 16;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int white = 0xFFFFFFFF;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, white);
            }
        }
        String rel = TexturePathResolver.ensureEntityLikePath(ctx.getMaterialPaths(), spriteKey, ctx.textureOptions());
        EntityTextureManager.registerGenerated(ctx, spriteKey, rel, img);
        return spriteKey;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static ConcurrentHashMap<Long, PlaneOffsetTracker> chunkPlaneOffsets(ExportContext ctx) {
        return ctx.session().computeAttribute("entity:plane-offsets", ConcurrentHashMap::new);
    }

    private static AtlasLocator atlasLocator(ExportContext ctx) {
        return ctx.session().computeAttribute(
            "entity-renderer:atlas-locator",
            () -> new DefaultAtlasLocator(ClientAccessHolder.get()));
    }
}


