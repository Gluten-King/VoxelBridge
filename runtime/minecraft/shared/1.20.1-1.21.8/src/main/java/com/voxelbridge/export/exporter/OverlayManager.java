package com.voxelbridge.export.exporter;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.core.ir.TintMode;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.core.util.color.ColorModeHandler;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.core.util.image.ImageSampling;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import com.voxelbridge.util.pool.ObjectPool;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Manages overlay quad caching and rendering.
 * Overlays are emitted in capture order.
 */
public final class OverlayManager {

    private final ExportContext ctx;
    private final Level level;
    private final double offsetX, offsetY, offsetZ;
    private final PlaneOffsetTracker planeOffset;

    private final List<OverlayQuadData> overlayCache = new ArrayList<>();

    // Object pools for memory efficiency
    private final ObjectPool<float[]> positions12Pool = new ObjectPool<>(256, () -> new float[12]);
    private final ObjectPool<float[]> uv8Pool = new ObjectPool<>(256, () -> new float[8]);
    private final ObjectPool<int[]> int4Pool = new ObjectPool<>(128, () -> new int[4]);

    /**
     * Overlay quad data with all required rendering information.
     *
     * @param positions      World coordinates with offset applied
     * @param direction      Direction for visibility culling
     * @param materialKey    Base material key for overlay material name
     * @param materialSuffix Suffix to append to materialKey (e.g., "_overlay", "_hilight", or null)
     */
        private record OverlayQuadData(float[] positions, float[] normal, float[] uv, String spriteKey, int color,
                                       Direction direction, String materialKey, String materialSuffix) {
    }

    public OverlayManager(ExportContext ctx, Level level, double offsetX, double offsetY, double offsetZ, PlaneOffsetTracker planeOffset) {
        this.ctx = ctx;
        this.level = level;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.planeOffset = planeOffset;
    }

    /**
     * Clears all overlay caches. Call this before processing each block.
     */
    public void clear() {
        overlayCache.clear();
    }

    /**
     * Caches an overlay quad by its base material key.
     *
     * @param baseMaterialKey the material this overlay belongs to
     * @param state block state
     * @param pos block position
     * @param quad the overlay quad
     * @param randomOffset vanilla random offset
     * @param spriteKey sprite key
     */
    public void cacheOverlay(String baseMaterialKey, BlockState state, BlockPos pos,
                             QuadData quad, Vec3 randomOffset, String spriteKey) {
        cacheOverlayInternal(baseMaterialKey, state, pos, quad, randomOffset, spriteKey, "_overlay");
    }

    /**
     * Caches a hilight overlay quad.
     * Uses "_hilight" suffix for material key.
     */
    public void cacheHilight(String baseMaterialKey, BlockState state, BlockPos pos,
                             QuadData quad, Vec3 randomOffset, String spriteKey) {
        cacheOverlayInternal(baseMaterialKey, state, pos, quad, randomOffset, spriteKey, "_hilight");
    }

    /**
     * Caches an overlay quad with the provided material suffix.
     */
    private void cacheOverlayInternal(String baseMaterialKey, BlockState state, BlockPos pos,
                             QuadData quad, Vec3 randomOffset, String spriteKey, String materialSuffix) {
        if (baseMaterialKey == null || baseMaterialKey.isEmpty()) {
            baseMaterialKey = "unknown";
        }

        var sprite = quad.sprite();
        if (sprite == null) return;

        Direction dir = quad.direction();

        // Register dynamic overlay texture
        boolean isDynamicTexture = spriteKey.contains("_overlay")
            || spriteKey.matches(".*\\d+$")
            || !ctx.getMaterialPaths().containsKey(spriteKey);
        boolean isHilightSprite = spriteKey.toLowerCase().contains("_hilight");
        String effectiveSuffix = materialSuffix;
        if (isHilightSprite) {
            effectiveSuffix = "_hilight";
        }

        if (isDynamicTexture) {
            com.voxelbridge.export.texture.TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            if (ctx.getCachedSpriteImage(spriteKey) == null) {
                try {
                    BufferedImage image = ctx.readSprite(sprite);
                    if (image != null) {
                        ctx.cacheSpriteImage(spriteKey, image);
                    }
                } catch (Exception ignore) {}
            }
        }

        float[] positions = positions12Pool.acquire();
        float[] uv0 = uv8Pool.acquire();
        int[] vertexColors = int4Pool.acquire();
        float[] localPos = positions12Pool.acquire();

        try {
            int[] verts = quad.vertices();
            if (verts.length < 32) return;

            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();

            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            v1 = GeometryUtil.computeAnimatedV1(v0, v1, spriteWidth, spriteHeight);

            float du = GeometryUtil.computeUvDelta(u0, u1);
            float dv = GeometryUtil.computeUvDelta(v0, v1);

            // Extract local coordinates and UV/colors
            final int stride = 8;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                float vx = Float.intBitsToFloat(verts[base]);
                float vy = Float.intBitsToFloat(verts[base + 1]);
                float vz = Float.intBitsToFloat(verts[base + 2]);
                int argb = verts[base + 3];
                float uu = Float.intBitsToFloat(verts[base + 4]);
                float vv = Float.intBitsToFloat(verts[base + 5]);

                vertexColors[i] = argb;
                localPos[i * 3] = vx;
                localPos[i * 3 + 1] = vy;
                localPos[i * 3 + 2] = vz;

                GeometryUtil.normalizeUv(uu, vv, u0, v0, du, dv, uv0, i * 2);
            }

            // Skip hilight quads that are fully transparent in the hilight texture
            if (isHilightSprite && isQuadFullyTransparent(spriteKey, uv0)) {
                return;
            }

            // Convert to world coordinates
            float[] worldPos = VertexExtractor.localToWorld(localPos, pos, offsetX, offsetY, offsetZ, randomOffset);
            System.arraycopy(worldPos, 0, positions, 0, 12);

            int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
            float[] normal = GeometryUtil.computeFaceNormal(positions);

            if (dir == null) {
                dir = inferLocalOutwardDirection(localPos);
            }
            OverlayQuadData data = new OverlayQuadData(
                positions.clone(), normal, uv0.clone(),
                spriteKey, overlayColor, dir, baseMaterialKey, effectiveSuffix
            );
            overlayCache.add(data);
        } finally {
            int4Pool.release(vertexColors);
            positions12Pool.release(localPos);
            positions12Pool.release(positions);
            uv8Pool.release(uv0);
        }
    }

    private boolean isQuadFullyTransparent(String spriteKey, float[] uvNormalized) {
        BufferedImage img = ctx.getCachedSpriteImage(spriteKey);
        if (img == null) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return false;
        float[] uvBounds = new float[4];
        if (!GeometryUtil.computeUvBounds(uvNormalized, uvBounds)) {
            return false;
        }
        int[] bounds = new int[4];
        if (!ImageSampling.computePixelBounds(uvBounds, w, h, bounds)) {
            return false;
        }
        return ImageSampling.isRegionFullyTransparent(img, bounds[0], bounds[1], bounds[2], bounds[3]);
    }
    /**
     * Outputs all cached overlays to the scene sink with visibility culling.
     *
     * @param sceneSink the scene sink
     * @param state block state
     * @param cullChecker cull checker
     */
    public void outputOverlays(IrSink sceneSink, BlockState state, CullChecker cullChecker) {
        if (overlayCache.isEmpty()) return;

        for (OverlayQuadData overlay : overlayCache) {
            // Use suffix if provided (e.g., "_overlay", "_hilight"), otherwise use base materialKey
            // Logic updated: Append suffix to base key, ensuring we don't strip _emissive from base key
            String overlayMaterialKey = overlay.materialKey;
            
            if (overlay.materialSuffix != null && !overlay.materialSuffix.isEmpty()) {
                // e.g. "stone_emissive" + "_overlay" -> "stone_emissive_overlay"
                // e.g. "stone" + "_overlay" -> "stone_overlay"
                overlayMaterialKey += overlay.materialSuffix;
            }
            
            overlayMaterialKey = ctx.resolveMaterialKey(overlay.spriteKey, overlayMaterialKey);
            Direction dir = overlay.direction;

            // Apply occlusion culling
            if (dir != null && cullChecker.shouldCull(dir)) {
                continue;  // Skip occluded overlay face
            }

            if (planeOffset != null) {
                planeOffset.applyOffset(overlay.positions, overlay.normal, dir);
            }

            // Output visible overlay
            boolean doubleSided = state.getBlock() instanceof BushBlock;
            ColorModeHandler.ColorData overlayColorData = ColorModeHandler.prepareColors(
                ctx.getColorMode(), ctx.getColorMapAccess(), overlay.color, true);
            ctx.registerSpriteMaterial(overlay.spriteKey, overlayMaterialKey);
            TintMode tintMode = ctx.getColorMode() != null && ctx.getColorMode().usesColormap()
                ? TintMode.COLORMAP
                : TintMode.VERTEX_COLOR;
            sceneSink.addQuad(overlayMaterialKey, overlay.spriteKey, overlay.spriteKey,
                RenderLayer.UNKNOWN, tintMode, doubleSided, false,
                overlay.positions, overlay.uv, overlayColorData.uv1(), overlay.normal,
                overlayColorData.colors());
        }
    }

    /**
     * Extracts overlay color from vertex colors or tint index.
     */
    private int extractOverlayColor(BlockState state, BlockPos pos, QuadData quad, int[] vertexColors) {
        for (int i = 0; i < 4; i++) {
            int argb = vertexColors[i];
            int rgb = argb & 0x00FFFFFF;
            if (rgb != 0x00FFFFFF) {
                return argb;
            }
        }

        int tintIndex = quad.tintIndex();
        if (tintIndex >= 0) {
            int argb = ClientAccessHolder.get().getMinecraft().getBlockColors().getColor(state, level, pos, tintIndex);
            return (argb == -1) ? 0xFFFFFFFF : argb;
        }

        return 0xFFFFFFFF;
    }

    private static Direction inferLocalOutwardDirection(float[] localPos) {
        if (localPos == null || localPos.length < 12) {
            return null;
        }
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < 4; i++) {
            cx += localPos[i * 3];
            cy += localPos[i * 3 + 1];
            cz += localPos[i * 3 + 2];
        }
        cx *= 0.25f;
        cy *= 0.25f;
        cz *= 0.25f;

        float dx = cx - 0.5f;
        float dy = cy - 0.5f;
        float dz = cz - 0.5f;

        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        float adz = Math.abs(dz);

        if (adx >= ady && adx >= adz) {
            return dx >= 0f ? Direction.EAST : Direction.WEST;
        }
        if (ady >= adx && ady >= adz) {
            return dy >= 0f ? Direction.UP : Direction.DOWN;
        }
        return dz >= 0f ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Functional interface for checking if a face should be culled.
     */
    @FunctionalInterface
    public interface CullChecker {
        boolean shouldCull(Direction face);
    }
}
