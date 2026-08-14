package com.voxelbridge.export.exporter.capture;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.MaterialSemantic;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.PlaneOffsetTracker;
import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.platform.render.capture.RenderCapture;
import com.voxelbridge.platform.render.capture.RenderCaptureUtil;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CapturedQuadProcessor {
    /** Canonical sprite key for the always-transparent material slot. */
    public static final String TRANSPARENT_SPRITE_KEY = "voxelbridge:transparent";

    private static final float[] EMPTY_UV = new float[8];
    private static final float[] NORMAL_UP = new float[] {
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f,
        0f, 1f, 0f
    };
    private static final Set<String> LOGGED_MISSING_UV = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_RENDER_ONLY_MASK = ConcurrentHashMap.newKeySet();

    private CapturedQuadProcessor() {}

    public interface TextureHandler<T> {
        TextureResult resolve(ExportContext ctx, T source, RenderType renderType,
                              RenderCaptureUtil.UvStats uvStats, float[] positions);
    }

    public interface UvMapper {
        void writeUvs(ExportContext ctx,
                      List<RenderCapture.Vertex> verts,
                      RenderCaptureUtil.UvStats uvStats,
                      boolean useAtlasUv,
                      float u0,
                      float u1,
                      float v0,
                      float v1,
                      String spriteKey,
                      ResolvedTexture textureRes,
                      float[] uv0);
    }

    public interface PlaneOffsetStrategy {
        void apply(PlaneOffsetTracker tracker, float[] positions, float[] faceNormal);
    }

    public record TextureResult(String spriteKey,
                                ResolvedTexture textureRes,
                                boolean isAtlasTexture,
                                float u0,
                                float u1,
                                float v0,
                                float v1,
                                boolean skip) {}

    public static void fillPositionsAndColors(List<RenderCapture.Vertex> verts, float[] positions, float[] colors) {
        int count = Math.min(4, verts.size());
        for (int i = 0; i < count; i++) {
            RenderCapture.Vertex v = verts.get(i);
            positions[i * 3] = v.x;
            positions[i * 3 + 1] = v.y;
            positions[i * 3 + 2] = v.z;

            colors[i * 4] = ((v.color >> 16) & 0xFF) / 255.0f;
            colors[i * 4 + 1] = ((v.color >> 8) & 0xFF) / 255.0f;
            colors[i * 4 + 2] = (v.color & 0xFF) / 255.0f;
            colors[i * 4 + 3] = ((v.color >> 24) & 0xFF) / 255.0f;
        }
    }

    public static <T> void process(
        ExportContext ctx,
        IrSink sceneSink,
        PlaneOffsetTracker planeOffset,
        RenderType renderType,
        List<RenderCapture.Vertex> verts,
        RenderCaptureUtil.UvStats uvStats,
        float[] positions,
        float[] colors,
        float[] uv0,
        T source,
        String materialGroupKey,
        TextureHandler<T> textureHandler,
        UvMapper uvMapper,
        PlaneOffsetStrategy planeOffsetStrategy,
        RenderTypeResolver renderTypeResolver
    ) {
        if (verts == null || verts.size() < 3) {
            return;
        }
        if (isRenderOnlyMask(renderType)) {
            String renderTypeName = String.valueOf(renderType);
            if (LOGGED_RENDER_ONLY_MASK.add(renderTypeName)) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.debug(
                    com.voxelbridge.util.debug.LogModule.ENTITY,
                    "[CapturedQuadProcessor] Skipping render-only mask: " + renderTypeName
                );
            }
            return;
        }
        RenderCaptureUtil.UvStats stats = uvStats != null ? uvStats : RenderCaptureUtil.computeUvStats(verts);
        if (!RenderCaptureUtil.hasCompleteUvs(stats)) {
            String renderTypeName = String.valueOf(renderType);
            if (LOGGED_MISSING_UV.add(renderTypeName)) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.debug(
                    com.voxelbridge.util.debug.LogModule.UV_REMAP,
                    "[CapturedQuadProcessor] Keeping quad without complete UVs; using (0,0) fallback: "
                        + renderTypeName
                );
            }
        }

        TextureResult result = textureHandler.resolve(ctx, source, renderType, stats, positions);
        if (result == null || result.skip() || result.spriteKey() == null) {
            return;
        }

        String spriteKey = result.spriteKey();
        ResolvedTexture textureRes = result.textureRes();
        boolean useAtlasUv = result.isAtlasTexture();
        float u0 = result.u0();
        float u1 = result.u1();
        float v0 = result.v0();
        float v1 = result.v1();

        if (useAtlasUv && textureRes != null && textureRes.sprite() != null) {
            float eps = 1e-4f;
            boolean outsideSpriteBounds =
                stats.minU() < textureRes.u0() - eps || stats.maxU() > textureRes.u1() + eps ||
                stats.minV() < textureRes.v0() - eps || stats.maxV() > textureRes.v1() + eps;
            if (outsideSpriteBounds) {
                useAtlasUv = false;
                u0 = 0f; u1 = 1f;
                v0 = 0f; v1 = 1f;
            }
        }

        uvMapper.writeUvs(ctx, verts, stats, useAtlasUv, u0, u1, v0, v1, spriteKey, textureRes, uv0);

        String resolvedMaterialKey = ctx.resolveMaterialKey(spriteKey, materialGroupKey);
        if (MaterialSemantic.isGlyph(materialGroupKey)) {
            // Individual-texture mode normally replaces the group key with the
            // sprite key. Preserve the glyph semantic in every atlas mode.
            resolvedMaterialKey = MaterialSemantic.glyph(resolvedMaterialKey);
        }
        ctx.registerSpriteMaterial(spriteKey, resolvedMaterialKey);
        RenderCaptureUtil.ColorModeResult colorResult =
            RenderCaptureUtil.applyColorMode(ctx, colors, EMPTY_UV);
        float[] faceNormal = GeometryUtil.computeFaceNormal(positions);
        planeOffsetStrategy.apply(planeOffset, positions, faceNormal);
        sceneSink.addQuad(resolvedMaterialKey, spriteKey, TRANSPARENT_SPRITE_KEY,
            RenderLayer.UNKNOWN, colorResult.tintMode(),
            renderTypeResolver.isDoubleSided(renderType),
            false,
            positions, uv0, colorResult.uv1(), faceNormal, colors);
    }

    private static boolean isRenderOnlyMask(RenderType renderType) {
        String name = String.valueOf(renderType).toLowerCase(java.util.Locale.ROOT);
        return name.contains("water_mask") || name.contains("watermask");
    }

}
