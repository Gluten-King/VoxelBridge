package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import com.voxelbridge.util.pool.ObjectPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
 * Overlays are organized by base material key to ensure correct attribution.
 */
public final class OverlayManager {

    private final ExportContext ctx;
    private final Level level;
    private final double offsetX, offsetY, offsetZ;

    // Cache overlays by their source block (baseMaterialKey)
    private final Map<String, List<OverlayQuadData>> overlayCacheByMaterial = new HashMap<>();

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

        OverlayQuadData(float[] positions, float[] normal, float[] uv,
                        String spriteKey, int color, Direction direction) {
            this.positions = positions;
            this.normal = normal;
            this.uv = uv;
            this.spriteKey = spriteKey;
            this.color = color;
            this.direction = direction;
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
        overlayCacheByMaterial.clear();
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
        if (baseMaterialKey == null || baseMaterialKey.isEmpty()) {
            baseMaterialKey = "unknown";
        }

        // Mark sprite as processed
        processedOverlaySprites.add(spriteKey);

        var sprite = quad.getSprite();
        if (sprite == null) return;

        Direction dir = quad.getDirection();

        // Register dynamic overlay texture
        boolean isDynamicTexture = spriteKey.contains("_overlay")
            || spriteKey.matches(".*\\d+$")
            || !ctx.getMaterialPaths().containsKey(spriteKey);

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

            // Get current overlay count for this material to determine z-offset index
            List<OverlayQuadData> overlayList = overlayCacheByMaterial.computeIfAbsent(baseMaterialKey, k -> new ArrayList<>());
            int overlayIndex = overlayList.size();

            // Apply overlay offset in local coordinates to prevent z-fighting
            VertexExtractor.applyOverlayOffset(localPos, overlayIndex);

            // Convert to world coordinates
            float[] worldPos = VertexExtractor.localToWorld(localPos, pos, offsetX, offsetY, offsetZ, randomOffset);
            System.arraycopy(worldPos, 0, positions, 0, 12);

            int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
            float[] normal = GeometryUtil.computeFaceNormal(positions);

            OverlayQuadData data = new OverlayQuadData(
                positions.clone(), normal, uv0.clone(),
                spriteKey, overlayColor, dir
            );
            overlayList.add(data);
        } finally {
            int4Pool.release(vertexColors);
            positions12Pool.release(localPos);
            positions12Pool.release(positions);
            uv8Pool.release(uv0);
        }
    }

    /**
     * Outputs all cached overlays to the scene sink with visibility culling.
     *
     * @param sceneSink the scene sink
     * @param state block state
     * @param cullChecker function to check if a face should be culled
     */
    public void outputOverlays(SceneSink sceneSink, BlockState state, CullChecker cullChecker) {
        for (Map.Entry<String, List<OverlayQuadData>> entry : overlayCacheByMaterial.entrySet()) {
            String materialKey = entry.getKey();
            List<OverlayQuadData> overlays = entry.getValue();
            if (overlays.isEmpty()) continue;

            String overlayMaterialKey = materialKey + "_overlay";

            for (OverlayQuadData overlay : overlays) {
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
