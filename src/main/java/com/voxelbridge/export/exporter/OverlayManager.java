package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import com.voxelbridge.util.pool.ObjectPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Manages overlay quad detection, caching, and rendering.
 * Overlays are organized by position to keep per-face overlay ordering stable.
 */
public final class OverlayManager {

    private final ExportContext ctx;
    private final Level level;
    private final double offsetX, offsetY, offsetZ;

    // Cache overlays by their position hash
    private final Map<Long, List<OverlayQuadData>> overlayCacheByPosition = new HashMap<>();

    // Track which sprites have been processed as overlays
    private final Set<String> processedOverlaySprites = new HashSet<>();

    // Object pools for memory efficiency
    private final ObjectPool<float[]> positions12Pool = new ObjectPool<>(256, () -> new float[12]);
    private final ObjectPool<float[]> uv8Pool = new ObjectPool<>(256, () -> new float[8]);
    private final ObjectPool<int[]> int4Pool = new ObjectPool<>(128, () -> new int[4]);

    /**
     * Overlay quad data with all required rendering information.
     */
    private static class OverlayQuadData {
        final float[] positions;     // World coordinates with offset applied
        final float[] normal;
        final float[] uv;
        final String spriteKey;
        final int color;
        final Direction direction;    // Direction for visibility culling
        final String materialKey;     // Base material key for overlay material name
        final String materialSuffix;  // Suffix to append to materialKey (e.g., "_overlay", "_hilight", or null)

        OverlayQuadData(float[] positions, float[] normal, float[] uv,
                        String spriteKey, int color, Direction direction, String materialKey, String materialSuffix) {
            this.positions = positions;
            this.normal = normal;
            this.uv = uv;
            this.spriteKey = spriteKey;
            this.color = color;
            this.direction = direction;
            this.materialKey = materialKey;
            this.materialSuffix = materialSuffix;
        }
    }

    public OverlayManager(ExportContext ctx, Level level, double offsetX, double offsetY, double offsetZ) {
        this.ctx = ctx;
        this.level = level;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    /**
     * Clears all overlay caches. Call this before processing each block.
     */
    public void clear() {
        overlayCacheByPosition.clear();
        processedOverlaySprites.clear();
    }

    /**
     * Checks if a sprite has been marked as processed overlay.
     */
    public boolean isProcessedOverlay(String spriteKey) {
        return processedOverlaySprites.contains(spriteKey);
    }

    /**
     * Detects if a sprite is a vanilla overlay (contains "_overlay" in name).
     */
    public static boolean isVanillaOverlay(String spriteKey) {
        return spriteKey != null && spriteKey.contains("_overlay");
    }

    /**
     * Extracts base material key from vanilla overlay sprite name.
     * Example: "minecraft:block/grass_block_overlay" -> "minecraft:grass_block"
     */
    public static String extractVanillaOverlayBase(String spriteKey) {
        if (spriteKey == null || !spriteKey.contains("_overlay")) {
            return null;
        }

        String key = spriteKey;
        String namespace = "minecraft";
        String path = key;

        // Parse namespace
        int colon = key.indexOf(':');
        if (colon >= 0) {
            namespace = key.substring(0, colon);
            path = key.substring(colon + 1);
        }

        // Remove "block/" prefix if present
        if (path.startsWith("block/")) {
            path = path.substring("block/".length());
        }

        // Remove "_overlay" suffix
        path = path.replace("_overlay", "");

        // Remove directional suffixes (e.g., _top, _side)
        path = path.replaceAll("_(top|side|bottom|north|south|east|west)$", "");

        return namespace + ":" + path;
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
                             BakedQuad quad, Vec3 randomOffset, String spriteKey) {
        cacheOverlayInternal(baseMaterialKey, state, pos, quad, randomOffset, spriteKey, "_overlay");
    }

    /**
     * Caches a hilight overlay quad.
     * Uses "_hilight" suffix for material key.
     */
    public void cacheHilight(String baseMaterialKey, BlockState state, BlockPos pos,
                             BakedQuad quad, Vec3 randomOffset, String spriteKey) {
        cacheOverlayInternal(baseMaterialKey, state, pos, quad, randomOffset, spriteKey, "_hilight");
    }

    /**
     * Caches an overlay quad without marking it as processed (for non-traditional overlays).
     * Uses original materialKey instead of appending "_overlay".
     */
    public void cacheOverlayNoMarkup(String baseMaterialKey, BlockState state, BlockPos pos,
                                     BakedQuad quad, Vec3 randomOffset, String spriteKey) {
        cacheOverlayInternal(baseMaterialKey, state, pos, quad, randomOffset, spriteKey, null);
    }

    private void cacheOverlayInternal(String baseMaterialKey, BlockState state, BlockPos pos,
                             BakedQuad quad, Vec3 randomOffset, String spriteKey, String materialSuffix) {
        if (baseMaterialKey == null || baseMaterialKey.isEmpty()) {
            baseMaterialKey = "unknown";
        }

        // Mark sprite as processed (all overlays skip PASS 2)
        processedOverlaySprites.add(spriteKey);

        var sprite = quad.getSprite();
        if (sprite == null) return;

        Direction dir = quad.getDirection();

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
                    BufferedImage image = TextureLoader.fromSprite(sprite);
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
            int[] verts = quad.getVertices();
            if (verts.length < 32) return;

            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();

            // Detect animated texture and adjust v1 to first frame
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            if (spriteHeight > spriteWidth) {
                int frameCount = spriteHeight / spriteWidth;
                float frameRatio = 1.0f / frameCount;
                v1 = v0 + (v1 - v0) * frameRatio;
            }

            float du = u1 - u0;
            if (du == 0) du = 1f;
            float dv = v1 - v0;
            if (dv == 0) dv = 1f;

            // Extract local coordinates and UV/colors
            final int stride = 8;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                float vx = Float.intBitsToFloat(verts[base]);
                float vy = Float.intBitsToFloat(verts[base + 1]);
                float vz = Float.intBitsToFloat(verts[base + 2]);
                int abgr = verts[base + 3];
                float uu = Float.intBitsToFloat(verts[base + 4]);
                float vv = Float.intBitsToFloat(verts[base + 5]);

                vertexColors[i] = abgr;
                localPos[i * 3] = vx;
                localPos[i * 3 + 1] = vy;
                localPos[i * 3 + 2] = vz;

                float su = (uu - u0) / du;
                float sv = (vv - v0) / dv;
                uv0[i * 2] = su;
                uv0[i * 2 + 1] = sv;
            }

            // Skip hilight quads that are fully transparent in the hilight texture
            if (isHilightSprite && isQuadFullyTransparent(spriteKey, uv0)) {
                return;
            }

            // Compute position hash before applying overlay offsets (per-face grouping)
            float[] baseWorldPos = VertexExtractor.localToWorld(localPos, pos, offsetX, offsetY, offsetZ, randomOffset);
            long posHash = computePositionHash(baseWorldPos);

            // Get current overlay count for this position to determine z-offset index
            List<OverlayQuadData> overlayList = overlayCacheByPosition.computeIfAbsent(posHash, k -> new ArrayList<>());
            int overlayIndex = overlayList.size();

            // Apply overlay offset in local coordinates to prevent z-fighting
            // hilight 使用更大的偏移（2x）避免与 overlay 共面；按 sprite 名判断
            boolean isHilight = isHilightSprite || "_hilight".equals(effectiveSuffix);
            float offsetMultiplier = isHilight ? 2f : 1f;
            int offsetIndex = isHilight ? 0 : overlayIndex;
            VertexExtractor.applyOverlayOffset(localPos, offsetIndex, offsetMultiplier, dir);

            // Convert to world coordinates
            float[] worldPos = VertexExtractor.localToWorld(localPos, pos, offsetX, offsetY, offsetZ, randomOffset);
            System.arraycopy(worldPos, 0, positions, 0, 12);

            int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
            float[] normal = GeometryUtil.computeFaceNormal(positions);

            OverlayQuadData data = new OverlayQuadData(
                positions.clone(), normal, uv0.clone(),
                spriteKey, overlayColor, dir, baseMaterialKey, effectiveSuffix
            );
            overlayList.add(data);
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
        float umin = 1f, umax = 0f, vmin = 1f, vmax = 0f;
        for (int i = 0; i < 4; i++) {
            float su = uvNormalized[i * 2];
            float sv = uvNormalized[i * 2 + 1];
            umin = Math.min(umin, su);
            umax = Math.max(umax, su);
            vmin = Math.min(vmin, sv);
            vmax = Math.max(vmax, sv);
        }
        int minX = Math.max(0, (int) Math.floor(umin * (w - 1)));
        int maxX = Math.min(w - 1, (int) Math.ceil(umax * (w - 1)));
        int minY = Math.max(0, (int) Math.floor(vmin * (h - 1)));
        int maxY = Math.min(h - 1, (int) Math.ceil(vmax * (h - 1)));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha != 0) {
                    return false; // found opaque pixel, keep quad
                }
            }
        }
        return true; // all covered pixels are transparent
    }
    /**
     * Outputs all cached overlays to the scene sink with visibility culling.
     *
     * @param sceneSink the scene sink
     * @param state block state
     * @param cullChecker function to check if a face should be culled
     */
    public void outputOverlays(SceneSink sceneSink, BlockState state, CullChecker cullChecker) {
        for (List<OverlayQuadData> overlays : overlayCacheByPosition.values()) {
            if (overlays.isEmpty()) continue;

            for (OverlayQuadData overlay : overlays) {
                // Use suffix if provided (e.g., "_overlay", "_hilight"), otherwise use base materialKey
                String overlayMaterialKey = overlay.materialSuffix != null
                    ? overlay.materialKey + overlay.materialSuffix
                    : overlay.materialKey;
                Direction dir = overlay.direction;

                // Apply occlusion culling
                if (dir != null && cullChecker.shouldCull(dir)) {
                    continue;  // Skip occluded overlay face
                }

                // Output visible overlay
                boolean doubleSided = state.getBlock() instanceof BushBlock;
                ColorModeHandler.ColorData overlayColorData = ColorModeHandler.prepareColors(ctx, overlay.color, true);
                ctx.registerSpriteMaterial(overlay.spriteKey, overlayMaterialKey);
                sceneSink.addQuad(overlayMaterialKey, overlay.spriteKey, overlay.spriteKey,
                    overlay.positions, overlay.uv, overlayColorData.uv1, overlay.normal,
                    overlayColorData.colors, doubleSided);
            }
        }
    }

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
            hash = 31 * hash + Math.round(positions[pi] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 1] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 2] * 100f);
        }
        return hash;
    }

    /**
     * Extracts overlay color from vertex colors or tint index.
     */
    private int extractOverlayColor(BlockState state, BlockPos pos, BakedQuad quad, int[] vertexColors) {
        for (int i = 0; i < 4; i++) {
            int abgr = vertexColors[i];
            int rgb = abgr & 0x00FFFFFF;
            if (rgb != 0x00FFFFFF) return 0xFF000000 | rgb;
        }

        if (quad.getTintIndex() >= 0) {
            int argb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
            return (argb == -1) ? 0xFFFFFFFF : argb;
        }

        return 0xFFFFFFFF;
    }

    /**
     * Functional interface for checking if a face should be culled.
     */
    @FunctionalInterface
    public interface CullChecker {
        boolean shouldCull(Direction face);
    }
}
