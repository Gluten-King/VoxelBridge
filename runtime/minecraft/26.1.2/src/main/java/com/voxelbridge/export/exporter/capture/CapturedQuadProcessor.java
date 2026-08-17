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
            if (ctx.session().firstOccurrence("render-only-mask", renderTypeName)) {
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
            if (ctx.session().firstOccurrence("missing-uv", renderTypeName)) {
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

        // ModelPart / submitModel capture emits sprite-local UVs in ~[0,1] (we skip
        // SpriteCoordinateExpander in CapturingSubmitNodeCollector). ResolvedTexture
        // still carries the MC sheet sub-rect. Un-expanding local UVs against that
        // rect clamps every vertex to the origin → pure-black chests. Detect local
        // UVs without requiring a large UV span (locks / small faces stay local too).
        if (useAtlasUv && textureRes != null) {
            float spanU = Math.max(1e-6f, textureRes.u1() - textureRes.u0());
            float spanV = Math.max(1e-6f, textureRes.v1() - textureRes.v0());
            boolean mcSpriteIsSubRect = spanU < 0.95f || spanV < 0.95f;
            boolean uvInUnitSquare =
                stats.minU() >= -0.05f && stats.maxU() <= 1.05f
                    && stats.minV() >= -0.05f && stats.maxV() <= 1.05f;
            if (mcSpriteIsSubRect && uvInUnitSquare) {
                useAtlasUv = false;
                u0 = 0f; u1 = 1f;
                v0 = 0f; v1 = 1f;
            } else if (textureRes.sprite() != null) {
                // Atlas-expanded capture (true SpriteCoordinateExpander path): keep
                // un-expand on mild half-texel overshoot; only drop bounds when UVs
                // look unit-square *and* far outside the MC sprite rect.
                float epsU = Math.max(1e-3f, spanU * 0.05f);
                float epsV = Math.max(1e-3f, spanV * 0.05f);
                boolean outsideSpriteBounds =
                    stats.minU() < textureRes.u0() - epsU || stats.maxU() > textureRes.u1() + epsU ||
                    stats.minV() < textureRes.v0() - epsV || stats.maxV() > textureRes.v1() + epsV;
                if (outsideSpriteBounds) {
                    boolean looksStandaloneLocal =
                        uvInUnitSquare
                            && (stats.maxU() - stats.minU()) > 0.2f
                            && (stats.maxV() - stats.minV()) > 0.2f
                            && (stats.minU() < textureRes.u0() - spanU
                                || stats.maxU() > textureRes.u1() + spanU
                                || stats.minV() < textureRes.v0() - spanV
                                || stats.maxV() > textureRes.v1() + spanV);
                    if (looksStandaloneLocal) {
                        useAtlasUv = false;
                        u0 = 0f; u1 = 1f;
                        v0 = 0f; v1 = 1f;
                    }
                }
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
            renderTypeResolver.resolveLayer(renderType), colorResult.tintMode(),
            renderTypeResolver.isDoubleSided(renderType),
            false,
            positions, uv0, colorResult.uv1(), faceNormal, colors);
    }

    private static boolean isRenderOnlyMask(RenderType renderType) {
        String name = String.valueOf(renderType).toLowerCase(java.util.Locale.ROOT);
        return name.contains("water_mask") || name.contains("watermask");
    }

}
