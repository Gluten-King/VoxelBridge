package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Format-agnostic sampler for fluid geometry.
 * Uses Minecraft's liquid renderer to generate fluid geometry.
 */
@OnlyIn(Dist.CLIENT)
public final class FluidExporter {
    private FluidExporter() {}

    /**
     * Samples fluid geometry and sends it to the scene sink.
     */
    public static void sample(ExportContext ctx,
                              SceneSink sceneSink,
                              Level level,
                              BlockState state,
                              BlockPos pos,
                              FluidState fluidState,
                              double offsetX,
                              double offsetY,
                              double offsetZ,
                              BlockPos regionMin,
                              BlockPos regionMax) {
        FluidState fs = fluidState != null ? fluidState : level.getFluidState(pos);
        if (fs == null || fs.isEmpty()) {
            return;
        }

        TextureAtlasSprite[] sprites = getFluidSprites(ctx, level, pos, fs);
        if (sprites == null || sprites.length < 1) {
            return;
        }

        // Use Fluid Name as Group Key (e.g. "minecraft:water")
        // This ensures all water faces are merged into one mesh, regardless of texture variants (still/flow)
        String fluidKey = BuiltInRegistries.FLUID.getKey(fs.getType()).toString();

        BlockRenderDispatcher dispatcher = ctx.getMc().getBlockRenderer();

        // Create a vertex consumer that forwards quads to the scene sink with coordinate offset
        QuadCollector collector = new QuadCollector(
            sceneSink, ctx, pos, sprites,
            offsetX, offsetY, offsetZ,
            regionMin, regionMax, fluidKey
        );

        dispatcher.renderLiquid(pos, level, collector, state, fs);
        collector.flush();
    }

    /**
     * Resolves fluid sprites using the same cache vanilla uses, with fallbacks.
     */
    private static TextureAtlasSprite[] getFluidSprites(ExportContext ctx,
                                                        BlockAndTintGetter level,
                                                        BlockPos pos,
                                                        FluidState fs) {
        // Primary path: Neo/vanilla fluid sprite cache (supports custom fluids)
        try {
            TextureAtlasSprite[] sprites = FluidSpriteCache.getFluidSprites(level, pos, fs);
            if (sprites != null && sprites.length >= 2) {
                TextureAtlasSprite still = sprites[0];
                TextureAtlasSprite flow = sprites[1];
                // Normalize nulls to missing sprite to avoid early bail-out
                TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
                TextureAtlasSprite missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
                if (still == null) still = missing;
                if (flow == null) flow = missing;
                return new TextureAtlasSprite[]{still, flow};
            }
        } catch (Throwable ignored) {
        }

        // Fallback: manual atlas lookup
        try {
            TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            String fluidName = fs.getType().toString();
            if (fluidName.startsWith("flowing_")) {
                fluidName = fluidName.substring("flowing_".length());
            }

            String namespace = "minecraft";
            String path = fluidName;
            if (fluidName.contains(":")) {
                String[] parts = fluidName.split(":", 2);
                namespace = parts[0];
                path = parts[1];
            }
            if (path.startsWith("flowing_")) {
                path = path.substring("flowing_".length());
            }

            String[][] pairs = new String[][]{
                    {"block/" + path + "_still", "block/" + path + "_flow"},
                    {"block/" + path + "_still", "block/" + path + "_flowing"},
                    {"block/" + path, "block/" + path + "_flow"},
                    {"block/" + path, "block/" + path + "_flowing"}
            };

            for (String[] pair : pairs) {
                ResourceLocation stillLoc = ResourceLocation.fromNamespaceAndPath(namespace, pair[0]);
                ResourceLocation flowLoc = ResourceLocation.fromNamespaceAndPath(namespace, pair[1]);
                TextureAtlasSprite still = atlas.getSprite(stillLoc);
                TextureAtlasSprite flow = atlas.getSprite(flowLoc);
                String stillKey = SpriteKeyResolver.resolve(still);
                String flowKey = SpriteKeyResolver.resolve(flow);

                if (stillKey.contains("missingno") || flowKey.contains("missingno")) {
                    continue;
                }

                return new TextureAtlasSprite[]{still, flow};
            }
        } catch (Throwable ignored) {
        }

        // Last resort: use missing texture so geometry still exports
        try {
            TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            TextureAtlasSprite missing = atlas.getSprite(MissingTextureAtlasSprite.getLocation());
            return new TextureAtlasSprite[]{missing, missing};
        } catch (Throwable ignored) {
            return new TextureAtlasSprite[0];
        }
    }
}
