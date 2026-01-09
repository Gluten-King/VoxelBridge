package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.resolve.AtlasLocator;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderLayerTextureResolver;
import com.voxelbridge.platform.render.capture.CaptureBufferBase;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import com.voxelbridge.platform.texture.TextureLoader;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * Captures entity renderer output into an IR sink.
 */
public final class EntityRenderer {

    private static final float[] EMPTY_UV = new float[8];
    private static final float[] NORMAL_UP = new float[] {
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f
    };
    private static AtlasLocator ATLAS_LOCATOR = new EntityAtlasLocator(ClientAccessHolder.get());
    private static TextureResolver<Entity> TEXTURE_RESOLVER = EntityTextureResolver.INSTANCE;
    private static RenderTypeResolver RENDER_TYPE_RESOLVER = RenderLayerTextureResolver.INSTANCE;

    private EntityRenderer() {}

    public static void setAtlasLocator(AtlasLocator locator) {
        if (locator != null) {
            ATLAS_LOCATOR = locator;
        }
    }

    public static void setTextureResolver(TextureResolver<Entity> resolver) {
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
                var pos = entity.getPos();
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Position] %s at world[%.3f, %.3f, %.3f] offset[%.3f, %.3f, %.3f] final[%.3f, %.3f, %.3f]",
                    entity.getType(),
                    pos.x, pos.y, pos.z,
                    offsetX, offsetY, offsetZ,
                    pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ));
            }

            EntityRenderDispatcher dispatcher = ctx.getMc().getEntityRenderDispatcher();
            net.minecraft.client.render.entity.EntityRenderer<? super Entity> renderer =
                dispatcher.getRenderer(entity);
            if (renderer == null) {
                VoxelBridgeLogger.error(LogModule.ENTITY, String.format(
                    "[ERROR] %s at [%.2f, %.2f, %.2f] - %s",
                    entity.getType(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    "No renderer available"));
                return false;
            }

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityRenderer] Using renderer: " + renderer.getClass().getSimpleName());

            MatrixStack matrices = new MatrixStack();

            double finalX = entity.getX() + offsetX;
            double finalY = entity.getY() + offsetY;
            double finalZ = entity.getZ() + offsetZ;

            if (entity instanceof AbstractDecorationEntity hangingEntity) {
                double[] hangingOffset = HangingEntityPositionUtil.calculateRenderOffset(hangingEntity);
                finalX += hangingOffset[0];
                finalY += hangingOffset[1];
                finalZ += hangingOffset[2];

                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[HangingEntity] Applied direction offset: offset=[%.4f, %.4f, %.4f]",
                    hangingOffset[0], hangingOffset[1], hangingOffset[2]));
            }

            matrices.translate(finalX, finalY, finalZ);

            CaptureBuffer captureBuffer = new CaptureBuffer(ctx, sceneSink, offsetX, offsetY, offsetZ, entity);
            float partial = 0f;
            float yaw = entity.getYaw();
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[Rotation] %s yaw=%.2fdeg (actual=%.2fdeg, isHanging=%s)",
                    entity.getType(),
                    yaw,
                    entity.getYaw(),
                    entity instanceof AbstractDecorationEntity));
            }

            int packedLight = 0xF000F0;

            boolean[] renderCompleted = new boolean[1];
            Exception[] renderException = new Exception[1];

            Runnable renderCall = () -> {
                try {
                    dispatcher.render(
                        entity,
                        0.0,
                        0.0,
                        0.0,
                        yaw,
                        partial,
                        matrices,
                        captureBuffer,
                        packedLight
                    );
                    renderCompleted[0] = true;
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
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
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
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    "No vertices were captured during render"));
            }

            return hadGeometry;
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.ENTITY, String.format(
                "[ERROR] %s at [%.2f, %.2f, %.2f] - %s",
                entity.getType(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                "Unexpected error: " + e.getMessage()), e);
            return false;
        }
    }

    private static class CaptureBuffer extends CaptureBufferBase {
        private final Entity entity;
        private int quadCount = 0;
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        CaptureBuffer(ExportContext ctx, IrSink sceneSink, double offsetX, double offsetY, double offsetZ, Entity entity) {
            super(ctx, sceneSink, null);
            this.entity = entity;
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
        public void onQuad(net.minecraft.client.render.RenderLayer renderLayer, List<RenderCapture.Vertex> verts) {
            if (verts.size() < 3) return;
            if (isShadowLayer(renderLayer)) {
                return;
            }

            recordGeometry();
            quadCount++;

            float[] positions = new float[12];
            float[] uv0 = new float[8];
            float[] colors = new float[16];

            for (int i = 0; i < Math.min(4, verts.size()); i++) {
                RenderCapture.Vertex v = verts.get(i);
                positions[i * 3] = v.x;
                positions[i * 3 + 1] = v.y;
                positions[i * 3 + 2] = v.z;

                minX = Math.min(minX, v.x);
                minY = Math.min(minY, v.y);
                minZ = Math.min(minZ, v.z);
                maxX = Math.max(maxX, v.x);
                maxY = Math.max(maxY, v.y);
                maxZ = Math.max(maxZ, v.z);

                colors[i * 4] = ((v.color >> 16) & 0xFF) / 255.0f;
                colors[i * 4 + 1] = ((v.color >> 8) & 0xFF) / 255.0f;
                colors[i * 4 + 2] = (v.color & 0xFF) / 255.0f;
                colors[i * 4 + 3] = ((v.color >> 24) & 0xFF) / 255.0f;
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
                    "[RenderLayer] %s renderLayer=%s",
                    entity.getType(),
                    renderLayer != null ? renderLayer.toString() : "null"));
            }
            ResolvedTexture textureRes = TEXTURE_RESOLVER.resolve(entity, renderLayer);
            String spriteKey;
            boolean isAtlasTexture = false;
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
            Identifier atlasLocation = textureRes != null ? textureRes.atlasLocation() : null;

            RenderCaptureUtil.UvStats uvStats = RenderCaptureUtil.computeUvStats(verts);

            if (textureRes != null && textureRes.isAtlasTexture() && textureRes.sprite() == null) {
                textureRes = RenderCaptureUtil.resolveAtlasSprite(textureRes, ATLAS_LOCATOR, uvStats, atlasLocation);
                if (textureRes != null) {
                    atlasLocation = textureRes.atlasLocation();
                }
            }

            String materialGroupKey = "entity:" + Registries.ENTITY_TYPE.getId(entity.getType());

            if (textureRes != null && textureRes.texture() != null) {
                Identifier textureId = textureRes.texture();
                if (entity instanceof net.minecraft.entity.decoration.painting.PaintingEntity) {
                    String path = textureId.getPath();
                    if (!path.startsWith("painting/") && !path.startsWith("textures/painting/") && path.indexOf('/') < 0) {
                        textureId = Identifier.of(textureId.getNamespace(), "painting/" + path);
                    }
                }
                EntityTextureManager.TextureHandle handle = entity instanceof AbstractClientPlayerEntity player
                    ? resolvePlayerTexture(ctx, player, textureId, renderLayer)
                    : null;
                if (handle == null) {
                    handle = EntityTextureManager.register(ctx, textureId.toString());
                }
                spriteKey = handle.spriteKey();
                isAtlasTexture = textureRes.isAtlasTexture();
                u0 = textureRes.u0(); u1 = textureRes.u1();
                v0 = textureRes.v0(); v1 = textureRes.v1();

                if (VoxelBridgeLogger.isDebugEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[Texture] %s texture=%s isAtlas=%s",
                        entity.getType(),
                        textureRes.texture() != null ? textureRes.texture() : "null",
                        isAtlasTexture));
                }
                if (VoxelBridgeLogger.isTraceEnabled(LogModule.ENTITY)) {
                    VoxelBridgeLogger.trace(LogModule.ENTITY, String.format(
                        "[UV] %s u=[%.4f, %.4f] v=[%.4f, %.4f]",
                        entity.getType(), u0, u1, v0, v1));
                }

                if (isAtlasTexture && textureRes.sprite() != null) {
                    BufferedImage spriteImg = ctx.getTextureAccess().readSprite(textureRes.sprite());
                    if (spriteImg != null) {
                        ctx.cacheSpriteImage(spriteKey, spriteImg);
                    }
                }
            } else {
                spriteKey = "entity:minecraft/white";
                VoxelBridgeLogger.debug(LogModule.ENTITY,
                    "[Quad#" + quadCount + "] No texture resolved, using white fallback");
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

            String resolvedMaterialKey = ctx.resolveMaterialKey(spriteKey, materialGroupKey);
            ctx.registerSpriteMaterial(spriteKey, resolvedMaterialKey);
            RenderCaptureUtil.ColorModeResult colorResult =
                RenderCaptureUtil.applyColorMode(ctx, colors, EMPTY_UV);
            boolean doubleSided = RENDER_TYPE_RESOLVER.isDoubleSided(renderLayer);
            sceneSink.addQuad(resolvedMaterialKey, spriteKey, "voxelbridge:transparent",
                RenderLayer.UNKNOWN, colorResult.tintMode(),
                doubleSided,
                false,
                positions, uv0, colorResult.uv1(), NORMAL_UP, colors);
        }

        private boolean isShadowLayer(net.minecraft.client.render.RenderLayer renderLayer) {
            if (renderLayer == null) {
                return false;
            }
            String name = renderLayer.toString().toLowerCase(Locale.ROOT);
            return name.contains("shadow");
        }

        private void fillUvs(List<RenderCapture.Vertex> verts, float[] uv0, boolean isAtlas, float u0, float u1, float v0, float v1) {
            int count = Math.min(4, verts.size());
            if (isAtlas) {
                RenderCaptureUtil.fillUvsAtlas(verts, uv0, u0, u1, v0, v1);
            } else {
                float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
                float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < count; i++) {
                    RenderCapture.Vertex v = verts.get(i);
                    minU = Math.min(minU, v.u);
                    maxU = Math.max(maxU, v.u);
                    minV = Math.min(minV, v.v);
                    maxV = Math.max(maxV, v.v);
                }

                float rangeU = maxU - minU;
                float rangeV = maxV - minV;

                boolean needsNormalization =
                    maxU > 1.1f || minU < -0.1f || maxV > 1.1f || minV < -0.1f ||
                    rangeU < 1e-6f || rangeV < 1e-6f;

                if (needsNormalization && rangeU > 1e-6f && rangeV > 1e-6f) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                        "[UV Normalization] Painting/Entity UV remapped: U[%.3f, %.3f] V[%.3f, %.3f] -> [0,1]x[0,1]",
                        minU, maxU, minV, maxV));
                    for (int i = 0; i < count; i++) {
                        RenderCapture.Vertex v = verts.get(i);
                        float su = (v.u - minU) / rangeU;
                        float sv = (v.v - minV) / rangeV;
                        uv0[i * 2] = Math.max(0f, Math.min(1f, su));
                        uv0[i * 2 + 1] = Math.max(0f, Math.min(1f, sv));
                    }
                } else if (rangeU < 1e-6f || rangeV < 1e-6f) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY,
                        "[UV Normalization] Degenerate UV detected, using [0,0] for all vertices");
                    for (int i = 0; i < count; i++) {
                        uv0[i * 2] = 0f;
                        uv0[i * 2 + 1] = 0f;
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        RenderCapture.Vertex v = verts.get(i);
                        uv0[i * 2] = Math.max(0f, Math.min(1f, v.u));
                        uv0[i * 2 + 1] = Math.max(0f, Math.min(1f, v.v));
                    }
                }
            }
        }
    }

    private static EntityTextureManager.TextureHandle resolvePlayerTexture(
        ExportContext ctx,
        AbstractClientPlayerEntity player,
        Identifier renderTexture,
        net.minecraft.client.render.RenderLayer renderLayer
    ) {
        PlayerTextures handles = ensurePlayerTextures(ctx, player);
        if (handles == null) {
            return null;
        }
        SkinTextures skinTextures = getPlayerSkinTextures(player);
        if (skinTextures != null && renderTexture != null) {
            if (renderTexture.equals(skinTextures.elytraTexture()) && handles.elytra() != null) {
                return handles.elytra();
            }
            if (renderTexture.equals(skinTextures.capeTexture()) && handles.cape() != null) {
                return handles.cape();
            }
            if (renderTexture.equals(skinTextures.texture()) && handles.skin() != null) {
                return handles.skin();
            }
        }
        String path = renderTexture != null ? renderTexture.getPath().toLowerCase(Locale.ROOT) : "";
        String layerName = renderLayer != null ? renderLayer.toString().toLowerCase(Locale.ROOT) : "";

        if ((path.contains("elytra") || layerName.contains("elytra") || layerName.contains("wing"))
            && handles.elytra() != null) {
            return handles.elytra();
        }
        if ((path.contains("cape") || path.contains("cloak") || layerName.contains("cape"))
            && handles.cape() != null) {
            return handles.cape();
        }

        if (path.contains("textures/atlas/")
            || path.contains("textures/item/")
            || path.contains("textures/block/")
            || path.contains("textures/models/")) {
            return null;
        }

        boolean isSkin =
            path.contains("skins/") || path.contains("skin/")
                || path.contains("textures/entity/player/")
                || path.endsWith("/steve.png")
                || path.endsWith("/alex.png");
        if (isSkin && handles.skin() != null) {
            return handles.skin();
        }
        return null;
    }

    private static PlayerTextures ensurePlayerTextures(ExportContext ctx, AbstractClientPlayerEntity player) {
        SkinTextures skinTextures = getPlayerSkinTextures(player);
        if (skinTextures == null) {
            return null;
        }
        String playerName = sanitizePlayerName(player.getGameProfile().getName());

        EntityTextureManager.TextureHandle skinHandle = null;
        EntityTextureManager.TextureHandle capeHandle = null;
        EntityTextureManager.TextureHandle elytraHandle = null;

        Identifier skin = skinTextures.texture();
        BufferedImage skinImg = readTextureWithFallback(skin, skinTextures.textureUrl());
        if (skinImg != null) {
            String key = "entity:player/skin/" + playerName;
            String relativePath = "textures/entity_textures/player/" + playerName + "_skin.png";
            skinHandle = EntityTextureManager.registerGenerated(ctx, key, relativePath, skinImg);
        }

        Identifier cape = skinTextures.capeTexture();
        BufferedImage capeImg = readTextureWithFallback(cape, null);
        if (capeImg != null) {
            String key = "entity:player/cape/" + playerName;
            String relativePath = "textures/entity_textures/player/" + playerName + "_cape.png";
            capeHandle = EntityTextureManager.registerGenerated(ctx, key, relativePath, capeImg);
        }

        Identifier elytra = skinTextures.elytraTexture();
        BufferedImage elytraImg = readTextureWithFallback(elytra, null);
        if (elytraImg != null) {
            String key = "entity:player/elytra/" + playerName;
            String relativePath = "textures/entity_textures/player/" + playerName + "_elytra.png";
            elytraHandle = EntityTextureManager.registerGenerated(ctx, key, relativePath, elytraImg);
        }

        if (skinHandle == null && capeHandle == null && elytraHandle == null) {
            return null;
        }
        return new PlayerTextures(skinHandle, capeHandle, elytraHandle);
    }

    private static SkinTextures getPlayerSkinTextures(AbstractClientPlayerEntity player) {
        try {
            PlayerListEntry entry = ClientAccessHolder.get().getMinecraft().getNetworkHandler()
                .getPlayerListEntry(player.getGameProfile().getId());
            if (entry != null) {
                return entry.getSkinTextures();
            }
        } catch (Exception ignored) {
        }
        try {
            return player.getSkinTextures();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BufferedImage readTextureWithFallback(Identifier texture, String url) {
        BufferedImage image = TextureLoader.readTexture(texture, ExportRuntimeConfig.isAnimationEnabled());
        if (image != null) {
            return image;
        }
        Identifier fallback = resolveTexturePathFallback(texture);
        if (fallback != null && !fallback.equals(texture)) {
            image = TextureLoader.readTexture(fallback, ExportRuntimeConfig.isAnimationEnabled());
            if (image != null) {
                return image;
            }
        }
        if (url != null && !url.isEmpty()) {
            try (java.io.InputStream in = new java.net.URL(url).openStream()) {
                image = javax.imageio.ImageIO.read(in);
                if (image != null && !ExportRuntimeConfig.isAnimationEnabled()) {
                    image = TextureLoader.extractFirstFrame(image);
                }
                return image;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Identifier resolveTexturePathFallback(Identifier texture) {
        if (texture == null) {
            return null;
        }
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
        return Identifier.of(texture.getNamespace(), path);
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

    private record PlayerTextures(
        EntityTextureManager.TextureHandle skin,
        EntityTextureManager.TextureHandle cape,
        EntityTextureManager.TextureHandle elytra
    ) {}
}
