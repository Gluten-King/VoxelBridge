package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Processes individual quads and outputs them to the scene sink (PASS 2 logic).
 * Handles tint colors, PBR textures, and dynamic texture registration.
 */
public final class QuadProcessor {

    private final ExportContext ctx;
    private final Level level;
    private final SceneSink sceneSink;
    private final double offsetX, offsetY, offsetZ;

    // Track which sprites have had PBR textures loaded
    private final Set<String> pbrLoadedSprites = new HashSet<>();

    // Track processed quads to avoid duplicates
    private final Set<Long> quadKeys = new HashSet<>();

    public QuadProcessor(ExportContext ctx, Level level, SceneSink sceneSink,
                         double offsetX, double offsetY, double offsetZ) {
        this.ctx = ctx;
        this.level = level;
        this.sceneSink = sceneSink;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    /**
     * Clears all caches. Call this before processing each block.
     */
    public void clear() {
        quadKeys.clear();
        // Note: pbrLoadedSprites is intentionally NOT cleared to avoid redundant loads
    }

    /**
     * Processes a single quad and outputs it to the scene sink.
     *
     * @param state block state
     * @param pos block position
     * @param quad the baked quad
     * @param blockKey material key for this block
     * @param randomOffset vanilla random offset
     */
    public void processQuad(BlockState state, BlockPos pos, BakedQuad quad,
                            String blockKey, Vec3 randomOffset) {
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) return;

        String spriteKey = SpriteKeyResolver.resolve(sprite);

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
                    BufferedImage img = TextureLoader.fromSprite(sprite);
                    if (img != null) ctx.cacheSpriteImage(spriteKey, img);
                } catch (Exception ignore) {}
            }
        }

        // Extract vertex data
        VertexExtractor.VertexData vertexData = VertexExtractor.extractFromQuad(
            quad, pos, sprite, offsetX, offsetY, offsetZ, randomOffset
        );

        boolean doubleSided = state.getBlock() instanceof BushBlock;

        // Check for duplicates
        long quadKey = computeQuadKey(spriteKey, vertexData.positions(), vertexData.normal(),
                                      doubleSided, vertexData.uvs());
        if (!quadKeys.add(quadKey)) return;

        // Compute tint color
        int argb = computeTintColor(state, pos, quad);

        // Prepare color data
        ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(ctx, argb, quad.getTintIndex() >= 0);

        // Register sprite material
        ctx.registerSpriteMaterial(spriteKey, blockKey);

        // Output quad
        sceneSink.addQuad(blockKey, spriteKey, null, vertexData.positions(), vertexData.uvs(),
            colorData.uv1, vertexData.normal(), colorData.colors, doubleSided);
    }

    /**
     * Computes tint color from block colors.
     */
    private int computeTintColor(BlockState state, BlockPos pos, BakedQuad quad) {
        if (quad.getTintIndex() < 0) return 0xFFFFFFFF;
        int argb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
        return (argb == -1) ? 0xFFFFFFFF : argb;
    }

    /**
     * Pre-loads PBR companion textures (normal and specular maps).
     */
    private void ensurePbrTexturesCached(TextureAtlasSprite sprite, String spriteKey) {
        if (sprite == null || spriteKey == null) return;
        // Use PbrTextureHelper's enhanced lookup logic
        com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, sprite);
    }

    /**
     * Computes unique key for quad deduplication.
     */
    private long computeQuadKey(String spriteKey, float[] positions, float[] normal,
                                boolean doubleSided, float[] uv0) {
        // Sort vertices to make key order-independent
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

            if (uv0 != null && uv0.length >= (idx * 2 + 2)) {
                hash = 31 * hash + Math.round(uv0[idx * 2] * 1000f);
                hash = 31 * hash + Math.round(uv0[idx * 2 + 1] * 1000f);
            }
        }

        return hash;
    }
}
