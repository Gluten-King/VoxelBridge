package com.voxelbridge.export.exporter;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import com.voxelbridge.platform.client.ClientAccessHolder;

/**
 * Format-agnostic sampler for fluid geometry.
 * Uses Minecraft's liquid renderer to generate fluid geometry.
 */
public final class FluidExporter {
    private FluidExporter() {}

    /**
     * Samples fluid geometry and sends it to the scene sink.
     */
    public static void sample(ExportContext ctx,
                              IrSink sceneSink,
                              World level,
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

        Sprite[] sprites = getFluidSprites(ctx, level, pos, fs);
        if (sprites == null || sprites.length < 1) {
            return;
        }

        // Use Fluid Name as Group Key (e.g. "minecraft:water")
        // This ensures all water faces are merged into one mesh, regardless of texture variants (still/flow)
        String fluidKey = Registries.FLUID.getId(fs.getFluid()).toString();

        BlockRenderManager dispatcher = ctx.getMc().getBlockRenderManager();

        // Create a vertex consumer that forwards quads to the scene sink with coordinate offset
        QuadCollector collector = new QuadCollector(
            sceneSink, ctx, pos, sprites,
            offsetX, offsetY, offsetZ,
            regionMin, regionMax, fluidKey
        );

        dispatcher.renderFluid(pos, level, collector, state, fs);
        collector.flush();
    }

    /**
     * Resolves fluid sprites using the same cache vanilla uses, with fallbacks.
     */
    private static Sprite[] getFluidSprites(ExportContext ctx,
                                                        BlockRenderView level,
                                                        BlockPos pos,
                                                        FluidState fs) {
        // Primary path: Fabric fluid render handler (supports custom fluids)
        try {
            FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fs.getFluid());
            if (handler != null) {
                Sprite[] sprites = handler.getFluidSprites(level, pos, fs);
                if (sprites != null && sprites.length >= 2) {
                    SpriteAtlasTexture atlas = ClientAccessHolder.get().getModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
                    Sprite missing = atlas.getSprite(MissingSprite.getMissingSpriteId());
                    Sprite still = sprites[0] != null ? sprites[0] : missing;
                    Sprite flow = sprites[1] != null ? sprites[1] : missing;
                    return new Sprite[]{still, flow};
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: vanilla atlas lookup by name
        // Fallback: manual atlas lookup
        try {
            SpriteAtlasTexture atlas = ClientAccessHolder.get().getModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            Identifier fluidId = Registries.FLUID.getId(fs.getFluid());
            String namespace = fluidId.getNamespace();
            String path = fluidId.getPath();

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
                Identifier stillLoc = Identifier.of(namespace, pair[0]);
                Identifier flowLoc = Identifier.of(namespace, pair[1]);
                Sprite still = atlas.getSprite(stillLoc);
                Sprite flow = atlas.getSprite(flowLoc);
                String stillKey = ctx.getTextureAccess().resolveSpriteKey(still);
                String flowKey = ctx.getTextureAccess().resolveSpriteKey(flow);

                if (stillKey.contains("missingno") || flowKey.contains("missingno")) {
                    continue;
                }

                return new Sprite[]{still, flow};
            }
        } catch (Throwable ignored) {
        }

        // Last resort: use missing texture so geometry still exports
        try {
            SpriteAtlasTexture atlas = ClientAccessHolder.get().getModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            Sprite missing = atlas.getSprite(MissingSprite.getMissingSpriteId());
            return new Sprite[]{missing, missing};
        } catch (Throwable ignored) {
            return new Sprite[0];
        }
    }
}
