package com.voxelbridge.export.exporter;

import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.scene.SceneSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Format-agnostic sampler for block geometry.
 * Extracts mesh data from blocks/fluids and feeds it to any SceneSink.
 *
 * This class knows about:
 * - Minecraft's BakedModel system
 * - Quad extraction and processing
 * - Occlusion culling
 *
 * This class does NOT know about:
 * - glTF, OBJ, or any specific output format
 * - File paths or output directories
 */
public final class BlockExporter {
    private final ExportContext ctx;
    private final SceneSink sceneSink;
    private final Level level;
    private BlockPos regionMin;
    private BlockPos regionMax;
    private double offsetX = 0;
    private double offsetY = 0;
    private double offsetZ = 0;

    // Cache for overlay quads: key = position hash, value = overlay UV data
    // Use position hash only for matching (no direction matching to avoid missing side faces)
    private final java.util.Map<Long, OverlayQuadData> overlayCache = new java.util.HashMap<>();
    // Backup cache: maps BlockPos to overlay data (for cases where position hash fails)
    private final java.util.Map<BlockPos, OverlayQuadData> overlayBlockPosCache = new java.util.HashMap<>();

    private static class OverlayQuadData {
        final float[] uv;           // overlay texture UV
        final float[] colorUv;      // overlay colormap UV
        final String spriteKey;     // overlay sprite name
        final int color;            // overlay color (for debugging)
        OverlayQuadData(float[] uv, float[] colorUv, String spriteKey, int color) {
            this.uv = uv;
            this.colorUv = colorUv;
            this.spriteKey = spriteKey;
            this.color = color;
        }
    }

    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.level = level;
    }

    /**
     * Sets the region bounds for boundary detection in occlusion culling.
     * Also calculates coordinate offset based on coordinate mode.
     */
    public void setRegionBounds(BlockPos min, BlockPos max) {
        this.regionMin = min;
        this.regionMax = max;

        // Calculate offset based on coordinate mode
        if (ctx.getCoordinateMode() == CoordinateMode.CENTERED) {
            // Center the model at origin
            offsetX = -(min.getX() + max.getX()) / 2.0;
            offsetY = -(min.getY() + max.getY()) / 2.0;
            offsetZ = -(min.getZ() + max.getZ()) / 2.0;
        } else {
            // Keep world coordinates
            offsetX = 0;
            offsetY = 0;
            offsetZ = 0;
        }
    }

    /**
     * Samples a single block and sends its geometry to the scene sink.
     */
    public void sampleBlock(BlockState state, BlockPos pos) {
        // Overlay pairing cache is only valid for the current block, so clear it before sampling a new one.
        overlayCache.clear();

        if (state.isAir()) return;

        // Handle fluids
        FluidState fluidState = state.getFluidState();
        if (fluidState != null && !fluidState.isEmpty()) {
            FluidExporter.sample(ctx, sceneSink, level, state, pos, fluidState, offsetX, offsetY, offsetZ);
        }

        // Handle BlockEntities first
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            com.voxelbridge.util.ExportLogger.log("[BlockExporter] Found BlockEntity: " + be.getClass().getSimpleName() + " at " + pos + ", export enabled: " + ctx.isBlockEntityExportEnabled());
            if (ctx.isBlockEntityExportEnabled()) {
                BlockEntityExportResult beResult = BlockEntityExporter.export(
                    ctx, level, state, be, pos, sceneSink,
                    offsetX, offsetY, offsetZ
                );

                com.voxelbridge.util.ExportLogger.log("[BlockExporter] BlockEntity export result: rendered=" + beResult.rendered() + ", replaceBlockModel=" + beResult.replaceBlockModel());

                if (beResult.replaceBlockModel()) {
                    // BlockEntity renderer replaces the block model entirely
                    return;
                }
                // If rendered but doesn't replace, continue with standard block rendering
            }
        }

        // Handle block geometry
        RenderShape shape = state.getRenderShape();
        if (shape == RenderShape.INVISIBLE) return;

        BakedModel model = getModel(state);
        if (model == null) return;

        // Occlusion culling optimization
        boolean isTransparent = !state.isSolidRender(level, pos);
        if (!isTransparent && isFullyOccluded(pos)) {
            return;
        }

        // Get model data (for BlockEntities)
        ModelData modelData = ModelData.EMPTY;
        if (be != null) {
            try {
                modelData = be.getModelData();
            } catch (Throwable ignored) {
            }
        }

        // Extract all quads
        List<BakedQuad> quads = getQuads(model, state, modelData, pos);
        if (quads.isEmpty()) return;

        // FIRST PASS: Cache all overlay quads
        for (BakedQuad quad : quads) {
            if (quad == null) continue;
            var sprite = quad.getSprite();
            if (sprite == null) continue;

            String spriteKey = com.voxelbridge.export.texture.SpriteKeyResolver.resolve(sprite);
            if (spriteKey.contains("_overlay")) {
                cacheOverlayQuad(state, pos, quad);
            }
        }

        // SECOND PASS: Process all quads (now overlay cache is populated)
        Set<Long> quadKeys = new HashSet<>();
        for (BakedQuad quad : quads) {
            if (quad == null) continue;

            // Face culling for opaque blocks
            if (!isTransparent) {
                Direction dir = quad.getDirection();
                if (dir != null && isFaceOccluded(pos, dir)) {
                    continue;
                }
            }

            processQuad(state, pos, quad, quadKeys);
        }
    }

    /**
     * Caches overlay quad UV data (texture UV + colormap UV) for later use by base quads.
     */
    private void cacheOverlayQuad(BlockState state, BlockPos pos, BakedQuad quad) {
        var sprite = quad.getSprite();
        if (sprite == null) return;

        String spriteKey = com.voxelbridge.export.texture.SpriteKeyResolver.resolve(sprite);

        // Extract vertex data
        float[] positions = new float[12];
        float[] uv0 = new float[8];

        // Get sprite UV bounds
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float du = u1 - u0;
        float dv = v1 - v0;
        if (du == 0) du = 1f;
        if (dv == 0) dv = 1f;

        // Extract vertex data from quad
        int[] verts;
        try {
            verts = quad.getVertices();
        } catch (Throwable t) {
            return;
        }
        if (verts.length < 32) return;

        final int stride = 8;
        int[] vertexColors = new int[4];
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base + 1]);
            float vz = Float.intBitsToFloat(verts[base + 2]);
            int abgr = verts[base + 3];  // ABGR packed color
            float uu = Float.intBitsToFloat(verts[base + 4]);
            float vv = Float.intBitsToFloat(verts[base + 5]);

            // Store vertex color
            vertexColors[i] = abgr;

            // World-space positions with coordinate offset
            positions[i * 3] = (float) (pos.getX() + vx + offsetX);
            positions[i * 3 + 1] = (float) (pos.getY() + vy + offsetY);
            positions[i * 3 + 2] = (float) (pos.getZ() + vz + offsetZ);

            // Normalize UVs to [0,1] range
            float su = (uu - u0) / du;
            float sv = (vv - v0) / dv;
            su = su - (float) Math.floor(su);
            sv = sv - (float) Math.floor(sv);
            su = clamp01(su);
            sv = clamp01(sv);

            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }

        // Compute overlay colormap UV for UV3
        int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
        float[] overlayColorUv = new float[8];
        var placement = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, overlayColor);

        // Map vertices to the corners of the UV bounding box
        overlayColorUv[0] = placement.u0(); overlayColorUv[1] = placement.v0(); // Top-left
        overlayColorUv[2] = placement.u1(); overlayColorUv[3] = placement.v0(); // Top-right
        overlayColorUv[4] = placement.u1(); overlayColorUv[5] = placement.v1(); // Bottom-right
        overlayColorUv[6] = placement.u0(); overlayColorUv[7] = placement.v1(); // Bottom-left

        // Compute position hash for overlay pairing
        long posHash = computePositionHash(positions);

        // Cache overlay data by both position hash and BlockPos
        OverlayQuadData data = new OverlayQuadData(uv0.clone(), overlayColorUv, spriteKey, overlayColor);
        overlayCache.put(posHash, data);
        overlayBlockPosCache.put(pos.immutable(), data);
    }

    /**
     * Processes a single quad and sends it to the scene sink.
     */
    private void processQuad(BlockState state,
                             BlockPos pos,
                             BakedQuad quad,
                             Set<Long> quadKeys) {
        var sprite = quad.getSprite();
        if (sprite == null) return;

        String spriteKey = com.voxelbridge.export.texture.SpriteKeyResolver.resolve(sprite);

        // Extract vertex data
        float[] positions = new float[12];
        float[] uv0 = new float[8];
        boolean doubleSided = state.getBlock() instanceof BushBlock;

        // Get sprite UV bounds
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float du = u1 - u0;
        float dv = v1 - v0;
        if (du == 0) du = 1f;
        if (dv == 0) dv = 1f;

        // Extract vertex data from quad
        int[] verts;
        try {
            verts = quad.getVertices();
        } catch (Throwable t) {
            return;
        }
        if (verts.length < 32) return;

        final int stride = 8;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base + 1]);
            float vz = Float.intBitsToFloat(verts[base + 2]);
            float uu = Float.intBitsToFloat(verts[base + 4]);
            float vv = Float.intBitsToFloat(verts[base + 5]);

            // World-space positions with coordinate offset
            positions[i * 3] = (float) (pos.getX() + vx + offsetX);
            positions[i * 3 + 1] = (float) (pos.getY() + vy + offsetY);
            positions[i * 3 + 2] = (float) (pos.getZ() + vz + offsetZ);

            // Normalize UVs to [0,1] range
            float su = (uu - u0) / du;
            float sv = (vv - v0) / dv;
            su = su - (float) Math.floor(su);
            sv = sv - (float) Math.floor(sv);
            su = clamp01(su);
            sv = clamp01(sv);

            uv0[i * 2] = su;
            uv0[i * 2 + 1] = sv;
        }

        // Compute face normal
        float[] faceNormal = computeFaceNormal(positions);

        // Deduplication check
        long quadKey = computeQuadKey(spriteKey, positions, faceNormal, doubleSided);
        if (!quadKeys.add(quadKey)) {
            return; // Skip duplicate
        }

        // Check if this is an overlay quad
        boolean isOverlay = spriteKey.contains("_overlay");

        // Always use colormap for biome colors (UV1)
        int argb = computeTintColor(state, pos, quad);
        float[] uv1 = new float[8];
        var placement = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, argb);

        // Map vertices to the corners of the UV bounding box
        uv1[0] = placement.u0(); uv1[1] = placement.v0();
        uv1[2] = placement.u1(); uv1[3] = placement.v0();
        uv1[4] = placement.u1(); uv1[5] = placement.v1();
        uv1[6] = placement.u0(); uv1[7] = placement.v1();

        float[] colors = whiteColor();

        // If this is a base quad, check if there's a cached overlay
        float[] uv2 = null;
        float[] uv3 = null;
        String uv3Source = "NONE";
        if (!isOverlay) {
            // Try position hash first, then BlockPos as fallback
            long posHash = computePositionHash(positions);
            OverlayQuadData overlayData = overlayCache.get(posHash);
            if (overlayData == null) {
                overlayData = overlayBlockPosCache.get(pos);
            }
            
            if (overlayData != null) {
                // Overlay found: use overlay texture and colormap UVs
                uv2 = overlayData.uv;
                uv3 = overlayData.colorUv;
                uv3Source = "overlay_colormap";
                
                // Register overlay texture and mapping
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(ctx, overlayData.spriteKey, 0xFFFFFF);
                ctx.getOverlayMappings().put(spriteKey, overlayData.spriteKey);
                
                // Log UV3 debug info
                com.voxelbridge.util.UV3DebugLogger.logQuadUV3(spriteKey, true, 
                    overlayData.spriteKey, uv3Source, uv3, overlayData.color);
            } else {
                // No overlay: use base color for UV3
                uv3 = new float[8];
                var fallbackPlacement = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, argb);
                uv3[0] = fallbackPlacement.u0(); uv3[1] = fallbackPlacement.v0();
                uv3[2] = fallbackPlacement.u1(); uv3[3] = fallbackPlacement.v0();
                uv3[4] = fallbackPlacement.u1(); uv3[5] = fallbackPlacement.v1();
                uv3[6] = fallbackPlacement.u0(); uv3[7] = fallbackPlacement.v1();
                uv3Source = "base_colormap";
                
                // Log UV3 debug info
                com.voxelbridge.util.UV3DebugLogger.logQuadUV3(spriteKey, false, 
                    null, uv3Source, uv3, argb);
            }
        }

        // Send to scene sink (format-agnostic)
        // Overlay quads export normally but without uv2/uv3
        // Base quads may have uv2/uv3 if overlay exists
        sceneSink.addQuad(spriteKey, positions, uv0, uv1, uv2, uv3, faceNormal, colors, doubleSided);
    }

    private float[] computeFaceNormal(float[] positions) {
        // Cross product of two edges
        float ax = positions[3] - positions[0];
        float ay = positions[4] - positions[1];
        float az = positions[5] - positions[2];
        float bx = positions[6] - positions[0];
        float by = positions[7] - positions[1];
        float bz = positions[8] - positions[2];

        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) {
            return new float[]{0, 1, 0};
        }
        return new float[]{nx / len, ny / len, nz / len};
    }

    /**
     * Computes biome tint color for a quad.
     * Returns ARGB color value for colormap registration.
     */
    private int computeTintColor(BlockState state, BlockPos pos, BakedQuad quad) {
        if (quad.getTintIndex() < 0) {
            return 0xFFFFFFFF;
        }

        int argb = Minecraft.getInstance().getBlockColors()
            .getColor(state, level, pos, quad.getTintIndex());
        
        return (argb == -1) ? 0xFFFFFFFF : argb;
    }

    private float[] whiteColor() {
        return new float[]{
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f
        };
    }


    /**
     * Computes a hash based only on quad positions for overlay pairing.
     * Overlay and base quads at the same position will have the same hash.
     */
    private long computePositionHash(float[] positions) {
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> {
            int ia = a * 3;
            int ib = b * 3;
            int cmpX = Float.compare(positions[ia], positions[ib]);
            if (cmpX != 0) return cmpX;
            int cmpY = Float.compare(positions[ia + 1], positions[ib + 1]);
            if (cmpY != 0) return cmpY;
            return Float.compare(positions[ia + 2], positions[ib + 2]);
        });

        long hash = 1125899906842597L;
        for (int idx : order) {
            int pi = idx * 3;
            // Use 100f (0.01 precision) instead of 1000f to be more tolerant of floating point differences
            hash = 31 * hash + Math.round(positions[pi] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 1] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 2] * 100f);
        }
        return hash;
    }

    private long computeQuadKey(String spriteKey, float[] positions, float[] normal, boolean doubleSided) {
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> {
            int ia = a * 3;
            int ib = b * 3;
            int cmpX = Float.compare(positions[ia], positions[ib]);
            if (cmpX != 0) return cmpX;
            int cmpY = Float.compare(positions[ia + 1], positions[ib + 1]);
            if (cmpY != 0) return cmpY;
            return Float.compare(positions[ia + 2], positions[ib + 2]);
        });

        long hash = 1125899906842597L;
        hash = 31 * hash + spriteKey.hashCode();

        if (!doubleSided) {
            hash = 31 * hash + Math.round(normal[0] * 1000f);
            hash = 31 * hash + Math.round(normal[1] * 1000f);
            hash = 31 * hash + Math.round(normal[2] * 1000f);
        }

        for (int idx : order) {
            int pi = idx * 3;
            hash = 31 * hash + Math.round(positions[pi] * 1000f);
            hash = 31 * hash + Math.round(positions[pi + 1] * 1000f);
            hash = 31 * hash + Math.round(positions[pi + 2] * 1000f);
        }
        return hash;
    }

    private boolean isFullyOccluded(BlockPos pos) {
        // Don't cull blocks on region boundary
        if (isOnRegionBoundary(pos)) {
            return false;
        }

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.isSolidRender(level, neighbor)) {
                return false;
            }
        }
        return true;
    }

    private boolean isFaceOccluded(BlockPos pos, Direction face) {
        // Don't cull faces on region boundary
        if (isOnRegionBoundary(pos)) {
            return false;
        }

        BlockPos neighbor = pos.relative(face);
        BlockState neighborState = level.getBlockState(neighbor);
        return neighborState.isSolidRender(level, neighbor);
    }

    /**
     * Checks if a block is on the region boundary.
     */
    private boolean isOnRegionBoundary(BlockPos pos) {
        if (regionMin == null || regionMax == null) {
            return false;
        }
        return pos.getX() == regionMin.getX() || pos.getX() == regionMax.getX()
            || pos.getY() == regionMin.getY() || pos.getY() == regionMax.getY()
            || pos.getZ() == regionMin.getZ() || pos.getZ() == regionMax.getZ();
    }

    private BakedModel getModel(BlockState state) {
        try {
            ModelManager mm = ctx.getMc().getModelManager();
            return mm.getBlockModelShaper().getBlockModel(state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<BakedQuad> getQuads(BakedModel model,
                                     BlockState state,
                                     ModelData data,
                                     BlockPos pos) {
        List<BakedQuad> quads = new ArrayList<>();
        RandomSource rand = RandomSource.create(
            shouldApplyVanillaRandom(state) ? computeBushSeed(pos) : 42L);

        try {
            for (Direction dir : Direction.values()) {
                List<BakedQuad> q = model.getQuads(state, dir, rand, data, null);
                if (q != null) quads.addAll(q);
            }
            List<BakedQuad> q2 = model.getQuads(state, null, rand, data, null);
            if (q2 != null) quads.addAll(q2);
        } catch (Throwable ignored) {
        }
        return quads;
    }

    private boolean shouldApplyVanillaRandom(BlockState state) {
        return state.is(Blocks.LILY_PAD);
    }

    private long computeBushSeed(BlockPos pos) {
        long seed = pos.getX() * 3129871L ^ pos.getZ() * 116129781L ^ pos.getY();
        return seed * seed * 42317861L + seed * 11L;
    }

    private float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Extracts overlay color from vertex colors or tint index.
     * Forces alpha=0xFF since overlay colors may have alpha=0 but valid RGB.
     */
    private int extractOverlayColor(BlockState state, BlockPos pos, BakedQuad quad, int[] vertexColors) {
        // Check vertex colors first (non-white RGB)
        for (int i = 0; i < 4; i++) {
            int abgr = vertexColors[i];
            int a = (abgr >> 24) & 0xFF;
            int b = (abgr >> 16) & 0xFF;
            int g = (abgr >> 8) & 0xFF;
            int r = abgr & 0xFF;
            int argb = (a << 24) | (r << 16) | (g << 8) | b;
            
            int rgb = argb & 0x00FFFFFF;
            if (rgb != 0x00FFFFFF) {
                return 0xFF000000 | rgb;  // Force alpha=0xFF
            }
        }

        // Fallback to tint index
        if (quad.getTintIndex() >= 0) {
            int argb = Minecraft.getInstance().getBlockColors()
                .getColor(state, level, pos, quad.getTintIndex());
            return (argb == -1) ? 0xFFFFFFFF : argb;
        }

        return 0xFFFFFFFF;
    }
}
