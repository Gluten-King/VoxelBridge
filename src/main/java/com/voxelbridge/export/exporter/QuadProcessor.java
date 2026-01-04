package com.voxelbridge.export.exporter;

import com.voxelbridge.config.ExportRuntimeConfig;
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

    // Placeholder to ensure tool reads the file first
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

        boolean hasBaked = hasBakedColors(vertexData.colors());

        ColorModeHandler.ColorData colorData;

        if (hasBaked) {
            // Prefer baked vertex colors (e.g., FRAPI-provided tint) over vanilla tint.
            if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP) {
                int bakedTint = extractBakedTintArgb(vertexData.colors());
                colorData = ColorModeHandler.prepareColors(ctx, bakedTint, true);
            } else {
                float[] linearColors = convertABGRtoLinearRGBA(vertexData.colors());
                colorData = new ColorModeHandler.ColorData(null, linearColors);
            }
        } else {
            // Compute tint color (returns -1 if no tint found)
            int tintColor = computeTintColor(state, pos, quad);
            if (tintColor != -1) {
                // Found a valid block tint color
                colorData = ColorModeHandler.prepareColors(ctx, tintColor, true);
            } else {
                // Default to white
                colorData = ColorModeHandler.prepareColors(ctx, 0xFFFFFFFF, false);
            }
        }

        String finalMaterialKey = blockKey;
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.INDIVIDUAL) {
            finalMaterialKey = spriteKey;
        }

        // Register sprite material (Intern strings)
        ctx.registerSpriteMaterial(spriteKey, finalMaterialKey);

        // Output quad (Intern keys)
        sceneSink.addQuad(ctx.intern(finalMaterialKey), ctx.intern(spriteKey), null, vertexData.positions(), vertexData.uvs(),
                colorData.uv1(), vertexData.normal(), colorData.colors(), doubleSided);
    }

    /**
     * Computes tint color from block colors. Returns -1 if no tint logic exists.
     */
    private int computeTintColor(BlockState state, BlockPos pos, BakedQuad quad) {
        if (quad.getTintIndex() < 0) return -1;
        return Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
    }

    private boolean hasBakedColors(int[] colors) {
        for (int c : colors) {
            if (c != 0xFFFFFFFF && c != -1) return true;
        }
        return false;
    }

    private float[] convertABGRtoLinearRGBA(int[] abgrColors) {
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            int c = abgrColors[i];
            // ABGR format: A=32-24, B=24-16, G=16-8, R=8-0
            // BUT: VertexExtractor.extractFromQuad reads directly from int[] verts.
            // If the raw format is ABGR (0xAABBGGRR), then:
            // R = c & 0xFF
            // G = (c >> 8) & 0xFF
            // B = (c >> 16) & 0xFF
            // A = (c >> 24) & 0xFF
            float r = srgbToLinearComponent(c & 0xFF);
            float g = srgbToLinearComponent((c >> 8) & 0xFF);
            float b = srgbToLinearComponent((c >> 16) & 0xFF);
            float a = ((c >> 24) & 0xFF) / 255.0f;
            
            int base = i * 4;
            out[base] = r;
            out[base + 1] = g;
            out[base + 2] = b;
            out[base + 3] = a;
        }
        return out;
    }

    private int extractBakedTintArgb(int[] abgrColors) {
        for (int c : abgrColors) {
            if (c != 0xFFFFFFFF && c != -1) {
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>> 8) & 0xFF;
                int r = c & 0xFF;
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return 0xFFFFFFFF;
    }

    private float srgbToLinearComponent(int c) {
        float v = c / 255.0f;
        if (v <= 0.04045f) {
            return v / 12.92f;
        }
        return (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
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
