package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.util.color.ColorModeHandler;
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

    // Track processed quads to avoid duplicates (Optimization: Use FastUtil primitive set)
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet quadKeys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

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

        // Register sprite material (Intern strings)
        ctx.registerSpriteMaterial(spriteKey, blockKey);

        // Output quad (Intern keys)
        sceneSink.addQuad(ctx.intern(blockKey), ctx.intern(spriteKey), null, vertexData.positions(), vertexData.uvs(),
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
     * Optimized to avoid object allocation (zero GC).
     */
    private long computeQuadKey(String spriteKey, float[] positions, float[] normal,
                                boolean doubleSided, float[] uv0) {
        // Primitive sort of indices based on vertex positions
        // We have 4 vertices (indices 0, 1, 2, 3)
        // Hardcoded bubble sort is faster than Arrays.sort for 4 elements and allocates nothing.
        int i0 = 0, i1 = 1, i2 = 2, i3 = 3;
        
        // Swap 0-1
        if (compareVerts(positions, i0, i1) > 0) { int t = i0; i0 = i1; i1 = t; }
        // Swap 2-3
        if (compareVerts(positions, i2, i3) > 0) { int t = i2; i2 = i3; i3 = t; }
        // Swap 0-2
        if (compareVerts(positions, i0, i2) > 0) { int t = i0; i0 = i2; i2 = t; }
        // Swap 1-3
        if (compareVerts(positions, i1, i3) > 0) { int t = i1; i1 = i3; i3 = t; }
        // Swap 1-2
        if (compareVerts(positions, i1, i2) > 0) { int t = i1; i1 = i2; i2 = t; }
        
        // Order is now i0, i1, i2, i3

        long hash = 1125899906842597L;
        hash = 31 * hash + spriteKey.hashCode();

        if (!doubleSided) {
            hash = 31 * hash + Math.round(normal[0] * 1000f);
            hash = 31 * hash + Math.round(normal[1] * 1000f);
            hash = 31 * hash + Math.round(normal[2] * 1000f);
        }
        
        // Hash vertices in sorted order
        hash = hashVertex(hash, positions, uv0, i0);
        hash = hashVertex(hash, positions, uv0, i1);
        hash = hashVertex(hash, positions, uv0, i2);
        hash = hashVertex(hash, positions, uv0, i3);

        return hash;
    }
    
    private int compareVerts(float[] pos, int idxA, int idxB) {
        int ia = idxA * 3;
        int ib = idxB * 3;
        int cmpX = Float.compare(pos[ia], pos[ib]);
        if (cmpX != 0) return cmpX;
        int cmpY = Float.compare(pos[ia + 1], pos[ib + 1]);
        if (cmpY != 0) return cmpY;
        return Float.compare(pos[ia + 2], pos[ib + 2]);
    }
    
    private long hashVertex(long hash, float[] positions, float[] uv0, int idx) {
        int pi = idx * 3;
        hash = 31 * hash + Math.round(positions[pi] * 1000f);
        hash = 31 * hash + Math.round(positions[pi + 1] * 1000f);
        hash = 31 * hash + Math.round(positions[pi + 2] * 1000f);

        if (uv0 != null && uv0.length >= (idx * 2 + 2)) {
            hash = 31 * hash + Math.round(uv0[idx * 2] * 1000f);
            hash = 31 * hash + Math.round(uv0[idx * 2 + 1] * 1000f);
        }
        return hash;
    }
}
