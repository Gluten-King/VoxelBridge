package com.voxelbridge.export.exporter;

import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.modhandler.ModHandledQuads;
import com.voxelbridge.modhandler.ModHandlerRegistry;
import com.voxelbridge.modhandler.ctm.CtmDetector;
import com.voxelbridge.modhandler.frapi.FabricApiHelper;
import com.voxelbridge.util.debug.ExportLogger;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplified block geometry exporter.
 * Delegates specialized tasks to dedicated managers and processors.
 */
public final class BlockExporter {
    private final ExportContext ctx;
    private final SceneSink sceneSink;
    private final SceneSink blockEntitySceneSink;
    private final Level level;
    private final ClientChunkCache chunkCache;
    private final SpriteFinder spriteFinder;
    private final boolean vanillaRandomTransformEnabled;
    private final BlockEntityRenderBatch blockEntityBatch;

    private BlockPos regionMin;
    private BlockPos regionMax;
    private double offsetX = 0;
    private double offsetY = 0;
    private double offsetZ = 0;

    // Managers for specialized tasks
    private OverlayManager overlayManager;
    private QuadProcessor quadProcessor;

    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private volatile boolean missingNeighborDetected = false;

    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level) {
        this(ctx, sceneSink, level, null, sceneSink);
    }

    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level, BlockEntityRenderBatch blockEntityBatch) {
        this(ctx, sceneSink, level, blockEntityBatch, sceneSink);
    }

    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level, BlockEntityRenderBatch blockEntityBatch, SceneSink blockEntitySceneSink) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.blockEntitySceneSink = blockEntitySceneSink != null ? blockEntitySceneSink : sceneSink;
        this.level = level;
        this.chunkCache = (level instanceof ClientLevel cl) ? cl.getChunkSource() : null;
        this.spriteFinder = SpriteFinder.get(ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS));
        this.vanillaRandomTransformEnabled = ctx.isVanillaRandomTransformEnabled();
        this.blockEntityBatch = blockEntityBatch;
    }

    public void setRegionBounds(BlockPos min, BlockPos max) {
        this.regionMin = min;
        this.regionMax = max;

        if (ctx.getCoordinateMode() == CoordinateMode.CENTERED) {
            offsetX = -(min.getX() + max.getX()) / 2.0;
            offsetY = -(min.getY() + max.getY()) / 2.0;
            offsetZ = -(min.getZ() + max.getZ()) / 2.0;
        } else {
            offsetX = 0;
            offsetY = 0;
            offsetZ = 0;
        }

        // Initialize managers with current offsets
        this.overlayManager = new OverlayManager(ctx, level, offsetX, offsetY, offsetZ);
        this.quadProcessor = new QuadProcessor(ctx, level, sceneSink, offsetX, offsetY, offsetZ);
    }

    /**
     * Samples a single block and outputs its geometry.
     */
    public void sampleBlock(BlockState state, BlockPos pos) {
        // Check neighbor chunks are loaded
        if (!isNeighborChunksLoadedForBlock(pos)) {
            ExportLogger.log("[BlockExporter] Neighbor chunks missing for block at " + pos.toShortString());
            missingNeighborDetected = true;
            return;
        }

        // Clear per-block caches
        overlayManager.clear();
        quadProcessor.clear();

        if (state.isAir()) return;

        // Vanilla random offset (grass, fern, etc.)
        Vec3 randomOffset = vanillaRandomTransformEnabled ? state.getOffset(level, pos) : Vec3.ZERO;

        // Export fluid
        FluidState fluidState = state.getFluidState();
        if (fluidState != null && !fluidState.isEmpty()) {
            FluidExporter.sample(ctx, sceneSink, level, state, pos, fluidState,
                offsetX, offsetY, offsetZ, regionMin, regionMax);
        }

        // Export block entity
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockExporter] Found BlockEntity: " + be.getClass().getSimpleName() + " at " + pos.toShortString() + ", isExportEnabled=" + ctx.isBlockEntityExportEnabled());
        }
        if (be != null && ctx.isBlockEntityExportEnabled()) {
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockExporter] Calling BlockEntityExporter.export for " + be.getClass().getSimpleName());
            BlockEntityExportResult beResult = BlockEntityExporter.export(ctx, level, state, be, pos,
                blockEntitySceneSink, offsetX, offsetY, offsetZ, blockEntityBatch);
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockExporter] BlockEntityExporter.export returned: rendered=" + beResult.rendered() + ", replaceBlockModel=" + beResult.replaceBlockModel());
            if (beResult.replaceBlockModel()) return;
        }

        // Skip invisible blocks
        if (state.getRenderShape() == RenderShape.INVISIBLE) return;

        // Get block model
        BakedModel model = ctx.getMc().getModelManager().getBlockModelShaper().getBlockModel(state);
        if (model == null) return;

        // Occlusion culling for opaque blocks
        boolean isTransparent = !state.isSolidRender(level, pos);
        if (!isTransparent && isFullyOccluded(pos)) return;

        // Get model data (for CTM/connected textures)
        ModelData modelData = getModelData(model, state, pos);

        // Get quads (via mod handlers or vanilla)
        ModHandledQuads handledQuads = ModHandlerRegistry.handle(ctx, level, state, be, pos, model);
        List<BakedQuad> quads = (handledQuads != null)
            ? handledQuads.quads()
            : getQuads(model, state, modelData, pos);

        if (quads.isEmpty()) return;

        // Detect CTM
        boolean isCTM = CtmDetector.isCTMModel(model, quads);

        // Generate material key
        String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int lightLevel = state.getLightEmission();
        if (lightLevel > 0) {
            blockKey = blockKey + "_emissive";
        }

        ctx.registerSpriteMaterial(blockKey, blockKey);

        // PASS 1: Detect and cache overlays
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) continue;

            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());

            // Check CTM overlay
            CtmDetector.CtmOverlayInfo ctmOverlay = CtmDetector.resolveCtmOverlay(quad.getSprite(), spriteKey, model);
            if (ctmOverlay != null && ctmOverlay.isOverlay()) {
                overlayManager.cacheOverlay(blockKey, state, pos, quad, randomOffset, spriteKey);
                continue;
            }

            // Check vanilla overlay
            if (OverlayManager.isVanillaOverlay(spriteKey)) {
                String vanillaBase = OverlayManager.extractVanillaOverlayBase(spriteKey);
                if (vanillaBase == null) vanillaBase = blockKey;
                overlayManager.cacheOverlay(vanillaBase, state, pos, quad, randomOffset, spriteKey);
                continue;  // Skip this quad in PASS 2
            }
        }

        // PASS 2: Process base quads
        for (BakedQuad quad : quads) {
            if (quad == null) continue;

            Direction dir = quad.getDirection();

            // Skip if processed as overlay
            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
            if (overlayManager.isProcessedOverlay(spriteKey)) {
                continue;
            }

            // Occlusion culling
            if (dir != null) {
                if (!isTransparent) {
                    // Opaque blocks: cull if neighbor is solid
                    if (isFaceOccluded(pos, dir)) continue;
                } else if (isCTM) {
                    // CTM transparent blocks: cull if neighbor is same block
                    if (isFaceOccludedBySameBlock(state, pos, dir)) continue;
                }
            }

            // Process quad
            quadProcessor.processQuad(state, pos, quad, blockKey, randomOffset);
        }

        // PASS 3: Output overlays with culling
        overlayManager.outputOverlays(sceneSink, state, dir -> {
            if (dir == null) return false;
            if (!isTransparent) {
                return isFaceOccluded(pos, dir);
            } else if (isCTM) {
                return isFaceOccludedBySameBlock(state, pos, dir);
            }
            return false;
        });
    }

    /**
     * Gets model data for CTM/connected textures support.
     */
    private ModelData getModelData(BakedModel model, BlockState state, BlockPos pos) {
        ModelData modelData = ModelData.EMPTY;
        try {
            modelData = level.getModelData(pos);
        } catch (Throwable ignored) {}

        try {
            if (model instanceof IBakedModelExtension extension) {
                modelData = extension.getModelData(level, pos, state, modelData);
            }
        } catch (Throwable ignored) {}

        return modelData;
    }

    /**
     * Gets quads from model, using Fabric API for CTM models.
     */
    private List<BakedQuad> getQuads(BakedModel model, BlockState state, ModelData data, BlockPos pos) {
        List<BakedQuad> quads = new ArrayList<>();

        long seed = state.is(Blocks.LILY_PAD) ? computeBushSeed(pos) : Mth.getSeed(pos.getX(), pos.getY(), pos.getZ());
        RandomSource rand = RandomSource.create(seed);

        // Try Fabric API for CTM models
        if (model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter()) {
            List<BakedQuad> fabricQuads = FabricApiHelper.extractQuads(fabricModel, level, state, pos, rand, spriteFinder);
            if (!fabricQuads.isEmpty()) {
                return fabricQuads;
            }
        }

        // Fallback to vanilla API
        try {
            for (Direction dir : Direction.values()) {
                List<BakedQuad> q = model.getQuads(state, dir, rand, data, null);
                if (q != null) quads.addAll(q);
            }
            List<BakedQuad> q2 = model.getQuads(state, null, rand, data, null);
            if (q2 != null) quads.addAll(q2);
        } catch (Throwable ignored) {}

        return quads;
    }

    // ===== Occlusion culling helpers =====

    private boolean isNeighborChunksLoadedForBlock(BlockPos pos) {
        if (chunkCache == null) return true;

        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        if (localX == 0 && isChunkMissing(cx - 1, cz)) return false;
        if (localX == 15 && isChunkMissing(cx + 1, cz)) return false;
        if (localZ == 0 && isChunkMissing(cx, cz - 1)) return false;
        if (localZ == 15 && isChunkMissing(cx, cz + 1)) return false;

        return true;
    }

    private boolean isChunkMissing(int cx, int cz) {
        var chunk = chunkCache.getChunk(cx, cz, false);
        return chunk == null || chunk.isEmpty();
    }

    private boolean isFullyOccluded(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            mutablePos.setWithOffset(pos, dir);
            if (isOutsideRegion(mutablePos)) return false;
            if (!isNeighborSolid(mutablePos)) return false;
        }
        return true;
    }

    private boolean isFaceOccluded(BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        if (isOutsideRegion(mutablePos)) return false;
        return isNeighborSolid(mutablePos);
    }

    private boolean isFaceOccludedBySameBlock(BlockState state, BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        if (isOutsideRegion(mutablePos)) return false;
        BlockState neighborState = level.getBlockState(mutablePos);
        return neighborState.getBlock() == state.getBlock();
    }

    private boolean isOutsideRegion(BlockPos pos) {
        if (regionMin == null || regionMax == null) return false;
        return pos.getX() < regionMin.getX() || pos.getX() > regionMax.getX()
            || pos.getY() < regionMin.getY() || pos.getY() > regionMax.getY()
            || pos.getZ() < regionMin.getZ() || pos.getZ() > regionMax.getZ();
    }

    private boolean isNeighborSolid(BlockPos neighbor) {
        if (chunkCache != null) {
            int cx = neighbor.getX() >> 4;
            int cz = neighbor.getZ() >> 4;
            var chunk = chunkCache.getChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) return true;
            BlockState state = chunk.getBlockState(neighbor);
            return state.isSolidRender(level, neighbor);
        }
        BlockState neighborState = level.getBlockState(neighbor);
        return neighborState.isSolidRender(level, neighbor);
    }

    private long computeBushSeed(BlockPos pos) {
        long seed = pos.getX() * 3129871L ^ pos.getZ() * 116129781L ^ pos.getY();
        return seed * seed * 42317861L + seed * 11L;
    }

    public boolean hadMissingNeighborAndReset() {
        boolean result = missingNeighborDetected;
        missingNeighborDetected = false;
        return result;
    }
}
