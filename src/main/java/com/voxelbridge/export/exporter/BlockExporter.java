package com.voxelbridge.export.exporter;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.modhandler.ModHandledQuads;
import com.voxelbridge.modhandler.ModHandlerRegistry;
import com.voxelbridge.modhandler.frapi.FabricApiHelper;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.export.util.geometry.VertexExtractor;
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
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
            VoxelBridgeLogger.debug(LogModule.SAMPLER_BLOCK, "[BlockExporter] Neighbor chunks missing for block at " + pos.toShortString());
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

        // Generate material key
        String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int lightLevel = state.getLightEmission();
        if (lightLevel > 0) {
            blockKey = blockKey + "_emissive";
        }

        ctx.registerSpriteMaterial(blockKey, blockKey);

        // PASS 1: Position-based CTM overlay detection
        detectCtmOverlaysByPosition(quads, state, pos, blockKey, randomOffset);

        // PASS 1b: Detect and cache overlays
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) continue;

            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());

            // Check vanilla overlay
            if (OverlayManager.isVanillaOverlay(spriteKey)) {
                String vanillaBase = OverlayManager.extractVanillaOverlayBase(spriteKey);
                if (vanillaBase == null) vanillaBase = blockKey;
                overlayManager.cacheOverlay(vanillaBase, state, pos, quad, randomOffset, spriteKey);
                continue;  // Skip this quad in PASS 2
            }

            // Check hilight overlay
            if (isHilightOverlay(spriteKey)) {
                // Hilight: bypass overlay suffix, let OverlayManager use sprite name (_hilight) for material/offset
                overlayManager.cacheOverlayNoMarkup(blockKey, state, pos, quad, randomOffset, spriteKey);
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
                }
                // Transparent blocks: no face culling (internal faces must remain visible)
            }

            // Process quad
            quadProcessor.processQuad(state, pos, quad, blockKey, randomOffset);
        }

        // PASS 3: Output overlays with culling
        overlayManager.outputOverlays(sceneSink, state, dir -> {
            if (dir == null) return false;
            if (!isTransparent) {
                return isFaceOccluded(pos, dir);
            }
            // Transparent blocks: no face culling for overlays
            return false;
        });
    }

    private void detectCtmOverlaysByPosition(List<BakedQuad> quads, BlockState state, BlockPos pos, String blockKey, Vec3 randomOffset) {
        record QuadEntry(int index, BakedQuad quad, String spriteKey, long posHash, boolean approxSquare,
                         float uMin, float uMax, float vMin, float vMax) {}

        Map<Long, List<QuadEntry>> groups = new HashMap<>();

        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            if (quad == null || quad.getSprite() == null) continue;

            var sprite = quad.getSprite();
            String spriteKey = SpriteKeyResolver.resolve(sprite);
            var vertexData = VertexExtractor.extractFromQuad(quad, pos, sprite, offsetX, offsetY, offsetZ, randomOffset);
            long posHash = computePositionHash(vertexData.positions());
            boolean approxSquare = isApprox1x1Square(vertexData.positions());

            float[] uv = vertexData.uvs();
            float uMin = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
            float uMax = Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]));
            float vMin = Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]));
            float vMax = Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7]));

            groups.computeIfAbsent(posHash, k -> new ArrayList<>())
                .add(new QuadEntry(i, quad, spriteKey, posHash, approxSquare, uMin, uMax, vMin, vMax));
        }

        for (List<QuadEntry> group : groups.values()) {
            if (group.size() < 2) continue;

            // CTM overlay 检测：正方形 + CTM sprite
            boolean hasCtmSprite = group.stream().anyMatch(q -> isCtmOverlaySprite(q.spriteKey()));
            QuadEntry first = group.get(0);
            if (!hasCtmSprite || !first.approxSquare()) continue;

            // 允许任意长方形，只要 UV AABB 尺寸一致（同形状）
            float du = first.uMax - first.uMin;
            float dv = first.vMax - first.vMin;
            final float UV_EPS = 1e-4f;
            boolean sameShape = group.stream().allMatch(q -> Math.abs((q.uMax - q.uMin) - du) < UV_EPS
                && Math.abs((q.vMax - q.vMin) - dv) < UV_EPS);
            if (!sameShape) continue;

            int minIndex = group.stream().mapToInt(QuadEntry::index).min().orElse(Integer.MAX_VALUE);

            group.stream()
                .sorted(Comparator.comparingInt(QuadEntry::index))
                .filter(q -> q.index() != minIndex)
                .forEach(q -> overlayManager.cacheOverlay(blockKey, state, pos, q.quad(), randomOffset, q.spriteKey()));
        }
    }

    private boolean isCtmOverlaySprite(String spriteKey) {
        if (spriteKey == null) return false;
        String lower = spriteKey.toLowerCase(Locale.ROOT);
        return lower.matches(".*_\\d+$")
            || lower.contains("/ctm/")
            || lower.contains("ctm/")
            || lower.contains("continuity");
    }

    private boolean isHilightOverlay(String spriteKey) {
        if (spriteKey == null) return false;
        return spriteKey.toLowerCase(Locale.ROOT).contains("_hilight");
    }

    private boolean isApprox1x1Square(float[] positions) {
        if (positions == null || positions.length < 12) return false;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float x = positions[i * 3];
            float y = positions[i * 3 + 1];
            float z = positions[i * 3 + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float SIZE_TOLERANCE = 0.01f;

        if (sizeX < SIZE_TOLERANCE) {
            return Math.abs(sizeY - 1f) <= SIZE_TOLERANCE && Math.abs(sizeZ - 1f) <= SIZE_TOLERANCE;
        }
        if (sizeY < SIZE_TOLERANCE) {
            return Math.abs(sizeX - 1f) <= SIZE_TOLERANCE && Math.abs(sizeZ - 1f) <= SIZE_TOLERANCE;
        }
        if (sizeZ < SIZE_TOLERANCE) {
            return Math.abs(sizeX - 1f) <= SIZE_TOLERANCE && Math.abs(sizeY - 1f) <= SIZE_TOLERANCE;
        }
        return false;
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

    private boolean isOutsideRegion(BlockPos pos) {
        if (regionMin == null || regionMax == null) return false;
        return pos.getX() < regionMin.getX() || pos.getX() > regionMax.getX()
            || pos.getY() < regionMin.getY() || pos.getY() > regionMax.getY()
            || pos.getZ() < regionMin.getZ() || pos.getZ() > regionMax.getZ();
    }

    private boolean isNeighborSolid(BlockPos neighbor) {
        BlockState state;
        if (chunkCache != null) {
            int cx = neighbor.getX() >> 4;
            int cz = neighbor.getZ() >> 4;
            var chunk = chunkCache.getChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) return true;
            state = chunk.getBlockState(neighbor);
        } else {
            state = level.getBlockState(neighbor);
        }

        // FILLCAVE: Treat any air with skylight 0 as solid for occlusion culling
        if (ExportRuntimeConfig.isFillCaveEnabled()) {
            if (state.isAir() && level.getBrightness(LightLayer.SKY, neighbor) == 0) {
                return true; // Pretend ALL cave_air is solid
            }
        }

        return state.isSolidRender(level, neighbor);
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
