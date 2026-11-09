package com.voxelbridge.export.exporter;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.core.ir.TintMode;
import com.voxelbridge.core.util.color.ColorUtil;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.core.util.color.ColorModeHandler;
import com.voxelbridge.core.util.geometry.QuadGeometryKey;
import com.voxelbridge.compat.BlockStateCompat;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes individual quads and outputs them to the scene sink (PASS 2 logic).
 * Handles tint colors, PBR textures, and dynamic texture registration.
 */
public final class QuadProcessor {

    private final ExportContext ctx;
    private final Level level;
    private final IrSink sceneSink;
    private final double offsetX, offsetY, offsetZ;
    private final PlaneOffsetTracker planeOffset;

    private final Set<String> pbrLoadedSprites = new HashSet<>();
    private final NonsolidGeometryCleaner nonsolidCleaner;

    // Pending quads for per-block dedup/cull decisions.
    private final List<PendingQuad> pendingQuads = new ArrayList<>();

    private static final float NORMAL_PARALLEL_DOT = 0.999f;

    private record PendingQuad(QuadGeometryKey key, BlockState state, BlockPos pos,
                               QuadData quad, Direction dir, Direction cullDir,
                               String materialKey, String spriteKey,
                               float[] positions, float[] uvs, float[] normal,
                               ColorModeHandler.ColorData colorData, boolean doubleSided,
                               RenderLayer renderLayer) {}
    public QuadProcessor(ExportContext ctx, Level level, IrSink sceneSink,
                         double offsetX, double offsetY, double offsetZ,
                         PlaneOffsetTracker planeOffset) {
        this.ctx = ctx;
        this.level = level;
        this.sceneSink = sceneSink;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.planeOffset = planeOffset;
        this.nonsolidCleaner = new NonsolidGeometryCleaner(ctx, level, offsetX, offsetY, offsetZ);
    }

    /**
     * Clears all caches. Call this before processing each block.
     */
    public void clear() {
        pendingQuads.clear();
        // Note: pbrLoadedSprites is intentionally NOT cleared to avoid redundant loads
    }

    public void clearBuckets() {
        nonsolidCleaner.clearBuckets();
    }

    /**
     * Processes a single quad and outputs it to the scene sink.
     *
     * @param state block state
     * @param pos block position
     * @param quad the quad data
     * @param blockKey material key for this block
     * @param randomOffset vanilla random offset
     */
    public void processQuad(BlockState state, BlockPos pos, QuadData quad,
                            String blockKey, Vec3 randomOffset) {
        processQuad(state, pos, quad, blockKey, randomOffset, null, null);
    }

    public void processQuad(BlockState state, BlockPos pos, QuadData quad,
                            String blockKey, Vec3 randomOffset,
                            VertexExtractor.VertexData vertexData) {
        processQuad(state, pos, quad, blockKey, randomOffset, vertexData, null);
    }

    public void processQuad(BlockState state, BlockPos pos, QuadData quad,
                            String blockKey, Vec3 randomOffset,
                            VertexExtractor.VertexData vertexData,
                            String spriteKeyOverride) {
        TextureAtlasSprite sprite = quad.sprite();
        if (sprite == null) return;

        String spriteKey = spriteKeyOverride != null ? spriteKeyOverride : ctx.resolveSpriteKey(sprite);

        // Load PBR textures (once per sprite)
        if (!pbrLoadedSprites.contains(spriteKey)) {
            ensurePbrTexturesCached(sprite, spriteKey);
            pbrLoadedSprites.add(spriteKey);
        }

        // Handle dynamic textures (CTM, numbered sprites)
        boolean isDynamic = spriteKey.matches(".*\\d+$") || !ctx.getMaterialPaths().containsKey(spriteKey);
        if (isDynamic) {
            com.voxelbridge.export.texture.TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            if (ctx.getCachedSpriteImage(spriteKey) == null) {
                try {
                    BufferedImage img = ctx.readSprite(sprite);
                    if (img != null) ctx.cacheSpriteImage(spriteKey, img);
                } catch (Exception ignore) {}
            }
        }

        // Extract vertex data
        VertexExtractor.VertexData data = vertexData != null
            ? vertexData
            : VertexExtractor.extractFromQuad(quad, pos, sprite, offsetX, offsetY, offsetZ, randomOffset);

        boolean hasBaked = ColorUtil.hasBakedColors(data.colors());
        boolean doubleSided = isDoubleSidedCutout(state);
        boolean exportDoubleSided = ctx.session().options().collapseDoubleSided();
        Direction dir = quad.direction();
        Direction cullDir = quad.cullDirection();
        if (exportDoubleSided && !BlockStateCompat.isSolidRender(state, level, pos)) {
            if (!nonsolidCleaner.registerSameFaceKey(blockKey, spriteKey, data.positions(), data.uvs(), data.normal())) {
                return;
            }
        }

        ColorModeHandler.ColorData colorData;
        int tintColor = -1;
        if (!hasBaked) {
            tintColor = computeTintColor(state, pos, quad);
        }

        int dedupTint = hasBaked
            ? ColorUtil.extractBakedTintArgb(data.colors())
            : (tintColor != -1 ? tintColor : 0xFFFFFFFF);

        if (hasBaked) {
            // Prefer baked vertex colors (e.g., FRAPI-provided tint) over vanilla tint.
            ColorMode mode = ctx.getColorMode();
            if (mode != null && mode.usesColormap()) {
                int bakedTint = ColorUtil.extractBakedTintArgb(data.colors());
                colorData = ColorModeHandler.prepareColors(
                    ctx.getColorMode(), ctx.getColorMapAccess(), bakedTint, true);
            } else {
                float[] linearColors = ColorUtil.convertArgbToLinearRgba(data.colors());
                colorData = new ColorModeHandler.ColorData(null, linearColors);
            }
        } else {
            // Compute tint color (returns -1 if no tint found)
            if (tintColor != -1) {
                // Found a valid block tint color
                colorData = ColorModeHandler.prepareColors(
                    ctx.getColorMode(), ctx.getColorMapAccess(), tintColor, true);
            } else {
                // Default to white
                colorData = ColorModeHandler.prepareColors(
                    ctx.getColorMode(), ctx.getColorMapAccess(), 0xFFFFFFFF, false);
            }
        }

        boolean torchShell = isTorchGlowShell(state, data.positions());
        String materialBase = blockKey;
        RenderLayer renderLayer = quad.renderLayer() != null ? quad.renderLayer() : RenderLayer.UNKNOWN;
        if (torchShell) {
            materialBase = blockKey + "_shell";
            renderLayer = RenderLayer.TRANSLUCENT;
            colorData = withVertexAlpha(colorData, 0.55f);
            doubleSided = true;
        }
        String materialKey = ctx.resolveMaterialKey(spriteKey, materialBase);
        QuadGeometryKey key = QuadGeometryKey.of(
            spriteKey.hashCode(), dedupTint, data.positions(), data.uvs()
        );
        pendingQuads.add(new PendingQuad(key, state, pos, quad, dir, cullDir, materialKey, spriteKey,
            data.positions(), data.uvs(), data.normal(), colorData, doubleSided, renderLayer));
    }

    /**
     * Flushes cached quads after a block is fully processed.
     */
    public void flush() {
        if (pendingQuads.isEmpty()) {
            return;
        }
        boolean exportDoubleSided = ctx.session().options().collapseDoubleSided();
        boolean nonsolidCulling = ctx.session().options().nonsolidCulling();
        Map<QuadGeometryKey, List<float[]>> normalsByKey = exportDoubleSided ? new HashMap<>() : null;

        for (PendingQuad quad : pendingQuads) {
            if (exportDoubleSided) {
                List<float[]> normals = normalsByKey.get(quad.key);
                if (normals == null) {
                    normals = new ArrayList<>(2);
                    normalsByKey.put(quad.key, normals);
                } else {
                    boolean parallel = false;
                    for (int i = 0; i < normals.size(); i++) {
                        if (areParallel(normals.get(i), quad.normal)) {
                            parallel = true;
                            break;
                        }
                    }
                    if (parallel) {
                        continue; // drop one of parallel faces
                    }
                }
                normals.add(normalize(quad.normal));
            }
            if (nonsolidCleaner.shouldCullAgainstSolid(quad.state, quad.pos, quad.quad, quad.cullDir)) {
                if (nonsolidCulling) {
                    continue;
                }
                nonsolidCleaner.applyInsetAgainstSolid(quad.cullDir, quad.positions);
            }
            nonsolidCleaner.applyInsetAgainstNonSolid(
                quad.state, quad.pos, quad.quad, quad.cullDir, quad.positions
            );
            ctx.registerSpriteMaterial(quad.spriteKey, quad.materialKey);
            if (planeOffset != null) {
                Direction offsetDir = quad.dir != null ? quad.dir : nonsolidCleaner.inferOutwardDirection(quad.positions, quad.pos);
                planeOffset.applyOffset(quad.positions, quad.normal, offsetDir);
            }
            TintMode tintMode = ctx.getColorMode() != null && ctx.getColorMode().usesColormap()
                ? TintMode.COLORMAP
                : TintMode.VERTEX_COLOR;
            sceneSink.addQuad(ctx.intern(quad.materialKey), ctx.intern(quad.spriteKey), null,
                quad.renderLayer != null ? quad.renderLayer : RenderLayer.UNKNOWN,
                tintMode, quad.doubleSided, false,
                quad.positions, quad.uvs, quad.colorData.uv1(), quad.normal,
                quad.colorData.colors());
        }
        pendingQuads.clear();
    }

    /**
     * Computes tint color from block colors. Returns -1 if no tint logic exists.
     */
    private int computeTintColor(BlockState state, BlockPos pos, QuadData quad) {
        int tintIndex = quad.tintIndex();
        if (tintIndex < 0) return -1;
        var tintSource = ClientAccessHolder.get().getMinecraft().getBlockColors().getTintSource(state, tintIndex);
        return tintSource != null
            ? tintSource.colorInWorld(state, (net.minecraft.client.renderer.block.BlockAndTintGetter) level, pos)
            : -1;
    }

    /**
     * Pre-loads PBR companion textures (normal and specular maps).
     */
    private void ensurePbrTexturesCached(TextureAtlasSprite sprite, String spriteKey) {
        if (sprite == null || spriteKey == null) return;
        // Use PbrTextureHelper's enhanced lookup logic
        com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, sprite);
    }

    private static boolean isDoubleSidedCutout(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        if (block instanceof BushBlock) return true;
        var key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return false;
        String path = key.getPath();
        return path.contains("torch")
            || path.contains("lantern")
            || path.equals("fire")
            || path.equals("soul_fire")
            || path.contains("redstone_wire")
            || path.equals("repeater")
            || path.equals("comparator");
    }

    private static boolean isTorchGlowShell(BlockState state, float[] positions) {
        if (state == null || positions == null || positions.length < 12) return false;
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) return false;
        String path = key.getPath();
        if (!(path.contains("torch") || path.equals("repeater") || path.equals("comparator"))) {
            return false;
        }
        float minX = positions[0], maxX = positions[0];
        float minY = positions[1], maxY = positions[1];
        float minZ = positions[2], maxZ = positions[2];
        for (int i = 1; i < 4; i++) {
            float x = positions[i * 3];
            float y = positions[i * 3 + 1];
            float z = positions[i * 3 + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        float sx = maxX - minX;
        float sy = maxY - minY;
        float sz = maxZ - minZ;
        final float flat = 1e-4f;
        final float minSpan = 0.15f;
        final float maxSpan = 0.30f;
        return (sx <= flat && sy >= minSpan && sy <= maxSpan && sz >= minSpan && sz <= maxSpan)
            || (sy <= flat && sx >= minSpan && sx <= maxSpan && sz >= minSpan && sz <= maxSpan)
            || (sz <= flat && sx >= minSpan && sx <= maxSpan && sy >= minSpan && sy <= maxSpan);
    }

    private static ColorModeHandler.ColorData withVertexAlpha(ColorModeHandler.ColorData source, float alpha) {
        if (source == null || source.colors() == null || source.colors().length < 16) return source;
        float[] colors = source.colors().clone();
        float clamped = Math.max(0f, Math.min(1f, alpha));
        for (int i = 0; i < 4; i++) colors[i * 4 + 3] = clamped;
        return new ColorModeHandler.ColorData(source.uv1(), colors);
    }

    private static float[] normalize(float[] n) {
        float lenSq = n[0] * n[0] + n[1] * n[1] + n[2] * n[2];
        if (lenSq < 1e-8f) {
            return new float[]{0f, 1f, 0f};
        }
        float inv = 1f / (float) Math.sqrt(lenSq);
        return new float[]{n[0] * inv, n[1] * inv, n[2] * inv};
    }

    private static boolean areParallel(float[] a, float[] b) {
        float ax = a[0], ay = a[1], az = a[2];
        float bx = b[0], by = b[1], bz = b[2];
        float lenA = ax * ax + ay * ay + az * az;
        float lenB = bx * bx + by * by + bz * bz;
        if (lenA < 1e-8f || lenB < 1e-8f) {
            return false;
        }
        float invA = 1f / (float) Math.sqrt(lenA);
        float invB = 1f / (float) Math.sqrt(lenB);
        float dot = (ax * invA) * (bx * invB) + (ay * invA) * (by * invB) + (az * invA) * (bz * invB);
        return Math.abs(dot) >= NORMAL_PARALLEL_DOT;
    }

}
