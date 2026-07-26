package com.voxelbridge.export.exporter;

import com.voxelbridge.compat.BlockStateCompat;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.adapter.QuadBatch;
import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.quad.QuadDataUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Simplified block geometry exporter.
 * Delegates specialized tasks to dedicated managers and processors.
 */
public final class BlockExporter {
    private final ExportContext ctx;
    private final IrSink sceneSink;
    private final IrSink blockEntitySceneSink;
    private final Level level;
    private final ClientChunkCache chunkCache;
    private final boolean vanillaRandomTransformEnabled;
    private final BlockEntityRenderBatch blockEntityBatch;
    private final PlaneOffsetTracker planeOffsetTracker = new PlaneOffsetTracker(3.0f, 1e-3f, 1e-3f);

    private BlockPos regionMin;
    private BlockPos regionMax;
    private double offsetX = 0;
    private double offsetY = 0;
    private double offsetZ = 0;

    // Managers for specialized tasks
    private OverlayManager overlayManager;
    private QuadProcessor quadProcessor;
    private BlockFaceVisibility faceVisibility;

    private volatile boolean missingNeighborDetected = false;

    public BlockExporter(ExportContext ctx, IrSink sceneSink, Level level) {
        this(ctx, sceneSink, level, null, sceneSink);
    }

    public BlockExporter(ExportContext ctx, IrSink sceneSink, Level level, BlockEntityRenderBatch blockEntityBatch) {
        this(ctx, sceneSink, level, blockEntityBatch, sceneSink);
    }

    public BlockExporter(ExportContext ctx, IrSink sceneSink, Level level, BlockEntityRenderBatch blockEntityBatch, IrSink blockEntitySceneSink) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.blockEntitySceneSink = blockEntitySceneSink != null ? blockEntitySceneSink : sceneSink;
        this.level = level;
        this.chunkCache = (level instanceof ClientLevel cl) ? cl.getChunkSource() : null;
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
        this.overlayManager = new OverlayManager(ctx, level, offsetX, offsetY, offsetZ, planeOffsetTracker);
        this.quadProcessor = new QuadProcessor(ctx, level, sceneSink, offsetX, offsetY, offsetZ, planeOffsetTracker);
        this.faceVisibility = new BlockFaceVisibility(level, chunkCache, regionMin, regionMax);
    }

    public void onChunkStart() {
        if (quadProcessor != null) {
            quadProcessor.clearBuckets();
        }
    }

    /**
     * Samples a single block and outputs its geometry.
     */
    public void sampleBlock(BlockState state, BlockPos pos) {
        // Check neighbor chunks are loaded only for chunk-edge blocks
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        if ((localX == 0 || localX == 15 || localZ == 0 || localZ == 15)
            && !faceVisibility.areNeighborChunksLoadedForBlock(pos)) {
            VoxelBridgeLogger.debug(LogModule.SAMPLER_BLOCK, "[BlockExporter] Neighbor chunks missing for block at " + pos.toShortString());
            missingNeighborDetected = true;
            return;
        }

        // Clear per-block caches
        overlayManager.clear();
        quadProcessor.clear();
        planeOffsetTracker.clear();

        if (state.isAir()) return;

        // Vanilla random offset (grass, fern, etc.)
        Vec3 randomOffset = vanillaRandomTransformEnabled
            ? BlockStateCompat.getOffset(state, level, pos)
            : Vec3.ZERO;

        // Export fluid
        FluidState fluidState = state.getFluidState();
        if (fluidState != null && !fluidState.isEmpty()) {
            FluidExporter.sample(ctx, sceneSink, level, state, pos, fluidState,
                offsetX, offsetY, offsetZ, regionMin, regionMax);
        }

        // Export block entity
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockExporter] Found BlockEntity: " + be.getClass().getSimpleName() + " at " + pos.toShortString() + ", isExportEnabled=" + ctx.isBlockEntityExportEnabled());
        }
        if (be != null && ctx.isBlockEntityExportEnabled()) {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockExporter] Calling BlockEntityExporter.export for " + be.getClass().getSimpleName());
            BlockEntityExportResult beResult = BlockEntityExporter.export(ctx, level, state, be, pos,
                blockEntitySceneSink, offsetX, offsetY, offsetZ, blockEntityBatch);
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockExporter] BlockEntityExporter.export returned: rendered=" + beResult.rendered() + ", replaceBlockModel=" + beResult.replaceBlockModel());
            if (beResult.replaceBlockModel()) return;
        }

        // Skip invisible blocks
        if (state.getRenderShape() == RenderShape.INVISIBLE) return;

        // Get block model via Adapter
        Object model = com.voxelbridge.adapter.Adapters.getRender().getBlockModel(state);
        if (model == null) return;

        // Occlusion culling for opaque blocks
        boolean isTransparent = !BlockStateCompat.isSolidRender(state, level, pos);
        byte[] faceOcclusionCache = null;
        if (!isTransparent) {
            faceOcclusionCache = faceVisibility.buildSolidFaceOcclusionCache(pos);
            if (faceOcclusionCache == null) return;
        }

        // Get quads via Adapter (handles ModelData and Fabric API internally)
        var modHandler = Adapters.getModHandler();
        List<BakedQuad> handledQuads = modHandler.shouldHandle(be)
            ? modHandler.handle(ctx, level, state, be, pos, model)
            : null;
        List<QuadData> quads;
        if (handledQuads != null) {
            quads = QuadDataUtil.wrapBakedQuads(handledQuads);
        } else {
            // Same formula as the deprecated Mth.getSeed(x,y,z) (still used by vanilla for lily pads etc.).
            long seed = GeometryUtil.computeBushSeed(pos.getX(), pos.getY(), pos.getZ());
            QuadBatch batch = com.voxelbridge.adapter.Adapters.getRender().getQuadBatch(model, state, pos, level, seed);
            quads = batch.quads();
        }

        if (quads.isEmpty()) return;

        int quadCount = quads.size();
        String[] spriteKeys = new String[quadCount];
        com.voxelbridge.export.util.geometry.VertexExtractor.VertexData[] vertexCache =
            new com.voxelbridge.export.util.geometry.VertexExtractor.VertexData[quadCount];
        boolean isCtmCompact = false;
        for (int i = 0; i < quadCount; i++) {
            QuadData quad = quads.get(i);
            var sprite = quad.sprite();
            if (quad == null || sprite == null) continue;
            String spriteKey = com.voxelbridge.adapter.Adapters.getRender().getSpriteName(sprite);
            // Per-block overrides (e.g. sign text baked into block/oak_sign for this pos only).
            String overridden = ctx.resolveBlockSpriteOverride(pos.asLong(), spriteKey);
            if (overridden != null && !overridden.equals(spriteKey)) {
                System.err.println("[VB-Sign] sprite override " + spriteKey + " -> " + overridden
                    + " @ " + pos.toShortString());
                spriteKey = overridden;
            }
            spriteKeys[i] = spriteKey;
            if (!isCtmCompact && OverlayClassifier.isContinuitySprite(spriteKey)) {
                isCtmCompact = true;
            }
            vertexCache[i] = com.voxelbridge.export.util.geometry.VertexExtractor.extractFromQuad(
                quad, pos, sprite, offsetX, offsetY, offsetZ, randomOffset
            );
        }

        // Generate material key
        String baseKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int lightLevel = state.getLightEmission();
        String blockKey = lightLevel > 0 ? baseKey + "_emissive" : baseKey;

        ctx.registerSpriteMaterial(blockKey, blockKey);

        // PASS 1: Detect and cache overlays (position-based; avoids CTM compact splitting)
        boolean[] isOverlay = new boolean[quadCount];
        OverlayClassifier.classifyByPosition(
            quads, spriteKeys, vertexCache, isOverlay, state, pos, blockKey, randomOffset,
            offsetX, offsetY, offsetZ, overlayManager
        );

        // PASS 2: Process base quads
        byte[] sameBlockOcclusionCache = null;
        for (int i = 0; i < quadCount; i++) {
            QuadData quad = quads.get(i);
            String spriteKey = spriteKeys[i];
            if (quad == null || spriteKey == null) continue;

            Direction dir = quad.direction();

            // Skip if processed as overlay
            if (isOverlay[i]) {
                continue;
            }

            // Occlusion culling
            if (dir != null) {
                if (!isTransparent) {
                    // Opaque blocks: cull if neighbor is solid
                    boolean occluded = faceVisibility.isFaceOccludedCached(pos, dir, faceOcclusionCache);
                    if (occluded) continue;
                } else if (isCtmCompact) {
                    // CTM compact: cull internal faces against same block to keep outer shell
                    int idx = dir.ordinal();
                    if (sameBlockOcclusionCache == null) {
                        sameBlockOcclusionCache = new byte[Direction.values().length];
                    }
                    byte cached = sameBlockOcclusionCache[idx];
                    boolean occluded;
                    if (cached == 0) {
                        occluded = faceVisibility.isFaceOccludedBySameBlock(state, pos, dir);
                        sameBlockOcclusionCache[idx] = (byte) (occluded ? 1 : 2);
                    } else {
                        occluded = cached == 1;
                    }
                    if (occluded) continue;
                }
                // Transparent blocks: no face culling (internal faces must remain visible)
            }

            // Process quad (use per-block overridden sprite key when present).
            // Generated BE sprites keep their own material key so they are not merged into the blank wood mesh.
            String matKey = (spriteKey != null && spriteKey.startsWith("blockentity:generated/"))
                ? spriteKey
                : blockKey;
            quadProcessor.processQuad(state, pos, quad, matKey, randomOffset, vertexCache[i], spriteKey);
        }

        // Flush cached quads (dedup/cull) before overlays
        quadProcessor.flush();

        // PASS 3: Output overlays with culling
        final boolean ctmCompact = isCtmCompact;
        final byte[] occlusionCacheFinal = faceOcclusionCache;
        final byte[] sameBlockCacheFinal = sameBlockOcclusionCache;
        overlayManager.outputOverlays(sceneSink, state, dir -> {
            if (dir == null) return false;
            if (!isTransparent) {
                return faceVisibility.isFaceOccludedCached(pos, dir, occlusionCacheFinal);
            }
            if (ctmCompact) {
                int idx = dir.ordinal();
                if (sameBlockCacheFinal == null) {
                    return faceVisibility.isFaceOccludedBySameBlock(state, pos, dir);
                }
                byte cached = sameBlockCacheFinal[idx];
                if (cached == 1) return true;
                if (cached == 2) return false;
                boolean occluded = faceVisibility.isFaceOccludedBySameBlock(state, pos, dir);
                sameBlockCacheFinal[idx] = (byte) (occluded ? 1 : 2);
                return occluded;
            }
            // Transparent blocks: no face culling for overlays
            return false;
        });
    }

    public boolean hadMissingNeighborAndReset() {
        boolean result = missingNeighborDetected;
        missingNeighborDetected = false;
        return result;
    }
}
