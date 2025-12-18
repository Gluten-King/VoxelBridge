package com.voxelbridge.export.exporter;

import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.exporter.blockentity.BlockEntityRenderBatch;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.export.util.GeometryUtil;
import com.voxelbridge.export.util.ColorModeHandler;
import com.voxelbridge.modhandler.ModHandledQuads;
import com.voxelbridge.modhandler.ModHandlerRegistry;
import com.voxelbridge.util.ExportLogger;
// Fabric API imports
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Format-agnostic sampler for block geometry.
 * Extracts mesh data from blocks/fluids and feeds it to any SceneSink.
 */
public final class BlockExporter {
    private final ExportContext ctx;
    private final SceneSink sceneSink;
    private final Level level;
    private final ClientChunkCache chunkCache;
    private final SpriteFinder spriteFinder;
    private final boolean vanillaRandomTransformEnabled;
    private final BlockEntityRenderBatch blockEntityBatch;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private BlockPos regionMin;
    private BlockPos regionMax;
    private double offsetX = 0;
    private double offsetY = 0;
    private double offsetZ = 0;
    // ===== NEW OVERLAY CACHE ARCHITECTURE (baseMaterialKey-based, not position-based) =====
    // Cache overlays by their source block (baseMaterialKey) instead of position hash
    // This ensures overlays are attributed to their true source (e.g., sand overlay stays with sand)
    // rather than the block they "land on" (e.g., grass)
    private final Map<String, List<OverlayQuadData>> overlayCacheByMaterial = new HashMap<>();
    // Track which sprites have been processed as overlays to avoid duplicates
    private final Set<String> processedOverlaySprites = new HashSet<>();
    // Cache CTM properties-based overlay detection by sprite path
    private final Map<String, CtmOverlayInfo> ctmOverlayCache = new HashMap<>();
    // Reflection-based cache for Continuity overlay processors (sprite -> overlay info)
    private static final Map<String, CtmOverlayInfo> continuityOverlayCache = new HashMap<>();
    private static boolean continuityOverlayScanned = false;
    private static boolean continuityOverlayRetryAllowed = true;
    // Track which sprites have already had their PBR textures loaded (deduplication)
    private final Set<String> pbrLoadedSprites = new HashSet<>();
    // Reusable scratch buffers to cut GC pressure during dense exports
    private final ObjectPool<float[]> positions12Pool = new ObjectPool<>(256, () -> new float[12]);
    private final ObjectPool<float[]> uv8Pool = new ObjectPool<>(256, () -> new float[8]);
    private final ObjectPool<int[]> int4Pool = new ObjectPool<>(128, () -> new int[4]);
    private final ObjectPool<QuadInfo> quadInfoPool = new ObjectPool<>(256, QuadInfo::new);
    private volatile boolean missingNeighborDetected = false;
    // Track if current block uses CTM/connected textures (for face culling decisions)
    private boolean currentBlockIsCTM = false;
    // CTM Debug logging
    private static final boolean CTM_LOG_ENABLED = true;
    // Disable legacy geometry-based CTM overlay detection; rely on properties-based detection instead
    private static final boolean USE_GEOMETRY_CTM_DETECTION = false;
    private static final float AXIS_NORMAL_DOT_MIN = 0.9999f;
    private static final float AXIS_COMPONENT_EPS = 1e-3f;
    private static final float PLANE_EPS = 1e-4f;
    private static final float SIZE_EPS = 1e-4f;
    private static final float UNIT_SIZE = 1.0f;
    private static final float UNIT_EPS = 1e-3f;
    // Only treat dirt path top overlays when the face sits near the upper surface (avoid bottom faces being treated as overlays)
    private static final float DIRT_PATH_TOP_MIN_HEIGHT = 15f / 16f - 0.001f;
    private static final float OVERLAY_ZFIGHT_OFFSET = 3e-4f;  // Increased by 2x for better multi-layer separation and float precision
    private static PrintWriter ctmDebugLog = null;
    private static int sampledBlockCount = 0;
    private static class OverlayQuadData {
        final float[] positions;  // World coordinates with offset applied
        final float[] normal;
        final float[] uv;
        final float[] colorUv;
        final String spriteKey;
        final int color;
        final boolean ctmOverlay;
        final int overlayIndex; // 0-based index for this overlay (for z-offset calculation)
        final String baseMaterialKey; // The base material this overlay belongs to (from connectTiles or block key)
        final boolean emissive; // Whether this overlay should be marked emissive
        final Direction direction; // Direction of the overlay quad for visibility culling
        OverlayQuadData(float[] positions, float[] normal, float[] uv, float[] colorUv, String spriteKey, int color, boolean ctmOverlay, int overlayIndex, String baseMaterialKey, boolean emissive, Direction direction) {
            this.positions = positions;
            this.normal = normal;
            this.uv = uv;
            this.colorUv = colorUv;
            this.spriteKey = spriteKey;
            this.color = color;
            this.ctmOverlay = ctmOverlay;
            this.overlayIndex = overlayIndex;
            this.baseMaterialKey = baseMaterialKey;
            this.emissive = emissive;
            this.direction = direction;
        }
    }
    private record CtmOverlayInfo(boolean isOverlay, String baseMaterialKey, String propertiesPath, Integer tileIndex) {}
    private static class QuadInfo {
        String sprite;
        float[] normal;
        int axis; // 0=X,1=Y,2=Z, -1 = not axis-aligned
        float planeCoord;
        float minU, maxU, minV, maxV;
        float minX, maxX, minZ, maxZ;
        int originalIndex; // Index in the original quads list (for render order)
        long posHash; // Position hash to avoid redundant vertex extraction

        QuadInfo reset(String sprite, float[] normal, int axis, float planeCoord, float minU, float maxU, float minV, float maxV,
                       float minX, float maxX, float minZ, float maxZ, int originalIndex, long posHash) {
            this.sprite = sprite;
            this.normal = normal;
            this.axis = axis;
            this.planeCoord = planeCoord;
            this.minU = minU;
            this.maxU = maxU;
            this.minV = minV;
            this.maxV = maxV;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.originalIndex = originalIndex;
            this.posHash = posHash;
            return this;
        }
    }
    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level) {
        this(ctx, sceneSink, level, null);
    }
    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level, BlockEntityRenderBatch blockEntityBatch) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.level = level;
        this.chunkCache = (level instanceof ClientLevel cl) ? cl.getChunkSource() : null;
        this.spriteFinder = SpriteFinder.get(ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS));
        this.vanillaRandomTransformEnabled = ctx.isVanillaRandomTransformEnabled();
        this.blockEntityBatch = blockEntityBatch;
    }
    public static void initializeCTMDebugLog(Path outDir) {
        if (!CTM_LOG_ENABLED) return;
        if (ctmDebugLog == null) {
            try {
                Path logPath = outDir.resolve("voxelbridge_ctm_debug.log");
                ctmDebugLog = new PrintWriter(new FileWriter(logPath.toFile(), false));
                ctmDebugLog.println("=== VoxelBridge CTM Debug Log ===");
                ctmDebugLog.println("Timestamp: " + System.currentTimeMillis());
                ctmDebugLog.flush();
                System.out.println("[VoxelBridge] CTM Debug Log initialized at: " + logPath);
            } catch (IOException e) {
                System.err.println("[VoxelBridge] Failed to create CTM debug log: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("[VoxelBridge] Failed to create CTM debug log (Unknown error): " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    private void logCtmDebug(String message) {
        if (CTM_LOG_ENABLED && ctmDebugLog != null) {
            try {
                ctmDebugLog.println(message);
                ctmDebugLog.flush();
            } catch (Exception ignored) {}
        }
    }
    public static void closeCTMDebugLog() {
        if (ctmDebugLog != null) {
            try {
                ctmDebugLog.println("\n=== Log Complete ===");
                ctmDebugLog.println("Total blocks sampled: " + sampledBlockCount);
                ctmDebugLog.close();
                ctmDebugLog = null;
                sampledBlockCount = 0;
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    public void setRegionBounds(BlockPos min, BlockPos max) {
        this.regionMin = min;
        this.regionMax = max;
        if (ctx.getCoordinateMode() == CoordinateMode.CENTERED) {
            offsetX = -(min.getX() + max.getX()) / 2.0;
            offsetY = -(min.getY() + max.getY()) / 2.0;
            offsetZ = -(min.getZ() + max.getZ()) / 2.0;
        } else {
            offsetX = 0; offsetY = 0; offsetZ = 0;
        }
    }
    public void sampleBlock(BlockState state, BlockPos pos) {
        if (!isNeighborChunksLoadedForBlock(pos)) {
            ExportLogger.log("[BlockExporter] Neighbor chunks missing for block at " + pos.toShortString() + ", skipping and retry later");
            missingNeighborDetected = true;
            return;
        }
        overlayCacheByMaterial.clear();
        processedOverlaySprites.clear();
        currentBlockIsCTM = false; // Reset for each block
        if (state.isAir()) return;
        // 原版位置哈希随机偏移（草/蕨等），可开关
        Vec3 randomOffset = vanillaRandomTransformEnabled ? state.getOffset(level, pos) : Vec3.ZERO;
        FluidState fluidState = state.getFluidState();
        if (fluidState != null && !fluidState.isEmpty()) {
            FluidExporter.sample(ctx, sceneSink, level, state, pos, fluidState, offsetX, offsetY, offsetZ, regionMin, regionMax);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && ctx.isBlockEntityExportEnabled()) {
            BlockEntityExportResult beResult = BlockEntityExporter.export(ctx, level, state, be, pos, sceneSink, offsetX, offsetY, offsetZ, blockEntityBatch);
            if (beResult.replaceBlockModel()) return;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE) return;
        BakedModel model = ctx.getMc().getModelManager().getBlockModelShaper().getBlockModel(state);
        if (model == null) return;
        boolean isTransparent = !state.isSolidRender(level, pos);
        if (!isTransparent && isFullyOccluded(pos)) return;
        ModelData modelData = getModelData(model, state, pos);
        boolean shouldLog = CTM_LOG_ENABLED && sampledBlockCount < 100;
        if (shouldLog && ctmDebugLog != null) {
            sampledBlockCount++;
            try {
                ctmDebugLog.println("\n--- Block #" + sampledBlockCount + " ---");
                ctmDebugLog.println("Position: " + pos.toShortString());
                ctmDebugLog.println("BlockState: " + state.toString());
                ctmDebugLog.println("Model Class: " + model.getClass().getName());
                if (model instanceof FabricBakedModel fabricModel) {
                    ctmDebugLog.println("Is FabricBakedModel: true");
                    ctmDebugLog.println("Is Vanilla Adapter: " + fabricModel.isVanillaAdapter());
                } else {
                    ctmDebugLog.println("Is FabricBakedModel: false");
                }
                ctmDebugLog.flush();
            } catch (Exception e) {
                // Ignore
            }
        }
        ModHandledQuads handledQuads = ModHandlerRegistry.handle(ctx, level, state, be, pos, model);
        List<BakedQuad> quads = (handledQuads != null)
            ? handledQuads.quads()
            : getQuads(model, state, modelData, pos);
        // Detect CTM after getting quads (need to analyze sprite diversity)
        if (!quads.isEmpty()) {
            currentBlockIsCTM = isCTMModel(model, quads);
        }
        if (shouldLog && ctmDebugLog != null) {
            try {
                ctmDebugLog.println("Quads returned: " + quads.size());
                ctmDebugLog.println("Is CTM (sprite analysis): " + currentBlockIsCTM);
                for (int i = 0; i < Math.min(quads.size(), 20); i++) {
                    BakedQuad quad = quads.get(i);
                    if (quad != null && quad.getSprite() != null) {
                        ctmDebugLog.println("  Quad[" + i + "]: sprite=" + quad.getSprite().contents().name()
                            + ", dir=" + quad.getDirection() + ", tint=" + quad.getTintIndex());
                    }
                }
                ctmDebugLog.flush();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (quads.isEmpty()) return;
        // Generate Material Group Key for this block
        // Format: "minecraft:glass"
        String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        // Detect emissive blocks and add suffix for renderer identification
        int lightLevel = state.getLightEmission();
        if (lightLevel > 0) {
            blockKey = blockKey + "_emissive";
        }
        // Map sprite->material for later animation naming/unification
        ctx.registerSpriteMaterial(blockKey, blockKey);
        // PASS 1: Identify and cache all overlays (vanilla + CTM)
        Map<Long, List<QuadInfo>> ctmPositionQuads = new HashMap<>();
        QuadInfo[] quadInfos = new QuadInfo[quads.size()];
        List<QuadInfo> borrowedQuadInfos = new ArrayList<>(quads.size());
        // Collect geometric info for all quads (needed for CTM detection)
        int quadIndex = 0;
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) {
                quadIndex++;
                continue;
            }
            float[] positions = positions12Pool.acquire();
            float[] uv0 = uv8Pool.acquire();
            try {
                extractVertices(quad, pos, positions, uv0, quad.getSprite(), randomOffset);
                float[] normal = GeometryUtil.computeFaceNormal(positions);
                String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
                // Properties-based CTM overlay detection (preferred)
                CtmOverlayInfo ctmOverlay = resolveCtmOverlay(quad.getSprite(), spriteKey, model);
                if (ctmOverlay != null && ctmOverlay.isOverlay()) {
                    // Always attribute CTM overlays to the current block (source) rather than the connected target
                    // block referenced by connectTiles. Using connectTiles here would incorrectly make the overlay
                    // belong to whatever block it is painted onto.
                    cacheOverlayByMaterial(blockKey, state, pos, quad, randomOffset, spriteKey);
                    quadIndex++;
                    continue; // skip adding overlay quads to base list
                }
                QuadInfo info = buildQuadInfo(spriteKey, positions, normal, quadIndex);
                quadInfos[quadIndex] = info;
                borrowedQuadInfos.add(info);
                long posHash = info.posHash;
                ctmPositionQuads.computeIfAbsent(posHash, k -> new ArrayList<>()).add(info);
            } finally {
                positions12Pool.release(positions);
                uv8Pool.release(uv0);
            }
            quadIndex++;
        }

        // ===== LEGACY GEOMETRY-BASED OVERLAY DETECTION (DISABLED) =====
        // These detection methods are disabled in favor of reflection-based detection.
        // They have O(n²) complexity and can misattribute overlays to the block they "land on"
        // rather than their true source block (e.g., sand overlay appearing on grass).
        // Reflection-based detection (resolveCtmOverlay) is O(1) with caching and uses
        // the authoritative connectTiles/matchTiles from CTM properties.

        /*
        // LEGACY: DIRT_PATH special geometry detection
        if (state.is(Blocks.DIRT_PATH)) {
            Map<Long, List<QuadInfo>> footprintGroups = new HashMap<>();
            for (List<QuadInfo> list : ctmPositionQuads.values()) {
                for (QuadInfo info : list) {
                    if (info.axis == 1) { // top/bottom faces
                        double baseY = pos.getY() + offsetY + (randomOffset != null ? randomOffset.y : 0);
                        float localY = (float) (info.planeCoord - baseY);
                        // Only treat faces that are near the top surface as overlay candidates
                        if (localY + PLANE_EPS < DIRT_PATH_TOP_MIN_HEIGHT) continue;
                        long fpHash = computeFootprintHash(info);
                        footprintGroups.computeIfAbsent(fpHash, k -> new ArrayList<>()).add(info);
                    }
                }
            }
            for (List<QuadInfo> group : footprintGroups.values()) {
                if (group.size() < 2) continue;
                QuadInfo baseInfo = null;
                for (QuadInfo info : group) {
                    if (baseInfo == null || info.planeCoord < baseInfo.planeCoord) {
                        baseInfo = info;
                    }
                }
                if (baseInfo == null) continue;
                // Use the pre-computed position hash instead of re-extracting vertices
                long baseHash = baseInfo.posHash;
                int overlayIdx = 0;
                for (QuadInfo info : group) {
                    if (info == baseInfo) continue;
                    if (info.planeCoord <= baseInfo.planeCoord + PLANE_EPS) continue;
                    BakedQuad overlayQuad;
                    try {
                        overlayQuad = quads.get(info.originalIndex);
                    } catch (Throwable t) { continue; }
                    cacheOverlayQuadWithBaseHash(state, pos, overlayQuad, true, overlayIdx++, randomOffset, baseHash, null);
                }
            }
        }
        */

        /*
        // LEGACY: PASS 1b - Position-based overlay detection (geometry analysis)
        for (Map.Entry<Long, List<QuadInfo>> entry : ctmPositionQuads.entrySet()) {
            List<QuadInfo> quadsAtPos = entry.getValue();
            if (quadsAtPos.size() < 2) continue; // Need at least base + overlay
            // Determine quad types at this position
            boolean hasCTM = false;
            boolean hasVanillaOverlay = false;
            for (QuadInfo info : quadsAtPos) {
                if (isCTMQuad(info.sprite)) hasCTM = true;
                if (isVanillaOverlayQuad(info.sprite)) hasVanillaOverlay = true;
            }
            // Find base quad (lowest originalIndex)
            int minIndex = Integer.MAX_VALUE;
            for (QuadInfo info : quadsAtPos) {
                if (info.originalIndex < minIndex) minIndex = info.originalIndex;
            }
            // Apply detection rules based on quad types at this position
            boolean handled = false;
            if (USE_GEOMETRY_CTM_DETECTION && hasCTM) {
                // Case A: CTM detection - require geometry validation
                boolean allValid = true;
                for (QuadInfo info : quadsAtPos) {
                    if (!isApprox1x1Square(info)) {
                        allValid = false;
                        break;
                    }
                }
                if (allValid) {
                    // Cache all non-base quads as CTM overlays with sequential indices
                    int overlayIdx = 0;
                    for (QuadInfo info : quadsAtPos) {
                        if (info.originalIndex > minIndex) {
                            // Find the actual BakedQuad from quads list
                            BakedQuad overlayQuad = quads.get(info.originalIndex);
                            cacheOverlayQuad(state, pos, overlayQuad, true, overlayIdx++, randomOffset, null);
                        }
                    }
                }
                handled = true;
            }
            if (!handled && hasVanillaOverlay) {
                // Case B: Vanilla overlay - sprite-based detection (no geometry check)
                int overlayIdx = 0;
                for (QuadInfo info : quadsAtPos) {
                    if (isVanillaOverlayQuad(info.sprite)) {
                        BakedQuad overlayQuad = quads.get(info.originalIndex);
                        // Extract base material key from vanilla overlay sprite name
                        // e.g., "minecraft:block/grass_block_overlay" -> "minecraft:grass_block"
                        String vanillaBase = extractVanillaOverlayBase(info.sprite);
                        cacheOverlayQuad(state, pos, overlayQuad, false, overlayIdx++, randomOffset, vanillaBase);
                    }
                }
            }
        }
        */

        // PASS 1c: Vanilla overlay detection (sprite-name based, no geometry)
        // Vanilla overlays don't have CTM properties, so we extract source from sprite name
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            if (quad == null || quad.getSprite() == null) continue;

            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());

            // Check if this is a vanilla overlay (sprite name contains "_overlay")
            if (isVanillaOverlayQuad(spriteKey)) {
                // Extract base material key from sprite name
                // e.g., "minecraft:block/grass_block_overlay" -> "minecraft:grass_block"
                String vanillaBase = extractVanillaOverlayBase(spriteKey);
                if (vanillaBase == null) vanillaBase = blockKey;

                // Cache by baseMaterialKey
                cacheOverlayByMaterial(vanillaBase, state, pos, quad, randomOffset, spriteKey);
            }
        }

        // ===== PASS 2: Process all quads and output geometry =====
        Set<Long> quadKeys = new HashSet<>();
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            QuadInfo quadInfo = (i < quadInfos.length) ? quadInfos[i] : null;
            if (quad == null) continue;
            Direction dir = quad.getDirection();
            if (dir != null) {
                if (!isTransparent) {
                    // Opaque blocks: cull if neighbor is solid
                    if (isFaceOccluded(pos, dir)) {
                        if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                            String blockName = state.getBlock().getName().getString();
                            if (blockName.contains("leaves") || blockName.contains("glass")) {
                                ctmDebugLog.println("  [CULLED] Opaque face " + dir + " at " + pos.toShortString());
                            }
                        }
                        continue;
                    }
                } else if (currentBlockIsCTM) {
                    // CTM transparent blocks (connected glass, etc.): only cull if neighbor is same block type
                    if (isFaceOccludedBySameBlock(state, pos, dir)) {
                        if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                            String blockName = state.getBlock().getName().getString();
                            if (blockName.contains("leaves") || blockName.contains("glass")) {
                                ctmDebugLog.println("  [CULLED] CTM transparent face " + dir + " at " + pos.toShortString());
                            }
                        }
                        continue;
                    }
                }
                // Regular transparent blocks (leaves, etc.): no culling
            }
            processQuad(state, pos, quad, quadInfo, quadKeys, blockKey, randomOffset);
        }

        // ===== PASS 3: Output all cached overlays WITH visibility culling =====
        // Overlays are now organized by baseMaterialKey, not position
        for (Map.Entry<String, List<OverlayQuadData>> entry : overlayCacheByMaterial.entrySet()) {
            String materialKey = entry.getKey();
            List<OverlayQuadData> overlays = entry.getValue();
            if (overlays.isEmpty()) continue;

            String overlayMaterialKey = materialKey + "_overlay";

            // Output overlays with same culling logic as base quads
            for (OverlayQuadData overlay : overlays) {
                Direction dir = overlay.direction;

                // Apply occlusion culling (same logic as PASS 2 base quads)
                if (dir != null) {
                    if (!isTransparent) {
                        // Opaque blocks: cull if neighbor is solid
                        if (isFaceOccluded(pos, dir)) {
                            continue;  // Skip occluded overlay face
                        }
                    } else if (currentBlockIsCTM) {
                        // CTM transparent blocks: only cull if neighbor is same block type
                        if (isFaceOccludedBySameBlock(state, pos, dir)) {
                            continue;  // Skip CTM overlay when connected
                        }
                    }
                    // Regular transparent blocks (leaves, etc.): no culling
                }

                // Output visible overlay
                boolean doubleSided = state.getBlock() instanceof BushBlock;
                ColorModeHandler.ColorData overlayColorData = ColorModeHandler.prepareColors(ctx, overlay.color, true);
                ctx.registerSpriteMaterial(overlay.spriteKey, overlayMaterialKey);
                sceneSink.addQuad(overlayMaterialKey, overlay.spriteKey, overlay.spriteKey, overlay.positions, overlay.uv,
                    overlayColorData.uv1, overlay.normal, overlayColorData.colors, doubleSided);
            }
        }

        for (QuadInfo info : borrowedQuadInfos) {
            quadInfoPool.release(info);
        }
    }
    private void processQuad(BlockState state, BlockPos pos, BakedQuad quad, QuadInfo quadInfo, Set<Long> quadKeys, String blockKey, Vec3 randomOffset) {
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) return;
        String spriteKey = SpriteKeyResolver.resolve(sprite);

        // Skip if this quad was already processed as an overlay in PASS 1
        if (processedOverlaySprites.contains(spriteKey)) {
            return;
        }

        // Try to load PBR companion textures for individual export (normal/specular)
        // Only load once per unique sprite to avoid redundant I/O operations
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
        float[] positions = new float[12];
        float[] uv0 = new float[8];
        boolean doubleSided = state.getBlock() instanceof BushBlock;
        extractVertices(quad, pos, positions, uv0, sprite, randomOffset);
        float[] normal = GeometryUtil.computeFaceNormal(positions);
        long quadKey = computeQuadKey(spriteKey, positions, normal, doubleSided, uv0);
        if (!quadKeys.add(quadKey)) return;

        // This is a base quad - output it
        int argb = computeTintColor(state, pos, quad);

        // Use ColorModeHandler to prepare colors
        ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColors(ctx, argb, quad.getTintIndex() >= 0);

        ctx.registerSpriteMaterial(spriteKey, blockKey);

        sceneSink.addQuad(blockKey, spriteKey, null, positions, uv0, colorData.uv1, normal, colorData.colors, doubleSided);
    }
    private void extractVertices(BakedQuad quad, BlockPos pos, float[] positions, float[] uv0, TextureAtlasSprite sprite, Vec3 randomOffset) {
        int[] verts = quad.getVertices();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        // Detect animated texture (height > width) and adjust v1 to first frame
        // TextureLoader.fromSprite() extracts first frame, so UV must match
        int spriteWidth = sprite.contents().width();
        int spriteHeight = sprite.contents().height();
        if (spriteHeight > spriteWidth) {
            // Animated texture: adjust v1 to cover only first frame
            int frameCount = spriteHeight / spriteWidth;
            float frameRatio = 1.0f / frameCount;
            v1 = v0 + (v1 - v0) * frameRatio;
            if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                ctmDebugLog.println(String.format("  [ANIMATED] %s: %dx%d -> %d frames, adjusted V: [%.4f, %.4f]",
                        sprite.contents().name(), spriteWidth, spriteHeight, frameCount, v0, v1));
            }
        }
        float du = u1 - u0; if(du==0) du=1f;
        float dv = v1 - v0; if(dv==0) dv=1f;
        for (int i=0; i<4; i++) {
            int base = i * 8;
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base+1]);
            float vz = Float.intBitsToFloat(verts[base+2]);
            float uu = Float.intBitsToFloat(verts[base+4]);
            float vv = Float.intBitsToFloat(verts[base+5]);
            // Use double precision to avoid precision loss in large scenes
            double worldX = pos.getX() + vx + offsetX + (randomOffset != null ? randomOffset.x : 0);
            double worldY = pos.getY() + vy + offsetY + (randomOffset != null ? randomOffset.y : 0);
            double worldZ = pos.getZ() + vz + offsetZ + (randomOffset != null ? randomOffset.z : 0);
            positions[i*3] = (float)worldX;
            positions[i*3+1] = (float)worldY;
            positions[i*3+2] = (float)worldZ;
            uv0[i*2] = (uu - u0) / du;
            uv0[i*2+1] = (vv - v0) / dv;
        }
    }
    private QuadInfo buildQuadInfo(String spriteKey, float[] positions, float[] normal, int originalIndex) {
        long posHash = computePositionHash(positions);
        return buildQuadInfo(spriteKey, positions, normal, originalIndex, posHash);
    }
    private QuadInfo buildQuadInfo(String spriteKey, float[] positions, float[] normal, int originalIndex, long posHash) {
        QuadInfo info = quadInfoPool.acquire();
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            int base = i * 3;
            float x = positions[base];
            float z = positions[base + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        int axis = dominantAxis(normal);
        if (axis < 0) {
            return info.reset(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, minX, maxX, minZ, maxZ, originalIndex, posHash);
        }
        float plane = positions[axis];
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            int base = i * 3;
            float coord = positions[base + axis];
            if (Math.abs(coord - plane) > PLANE_EPS) {
                return info.reset(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, minX, maxX, minZ, maxZ, originalIndex, posHash);
            }
            float u, v;
            switch (axis) {
                case 0 -> { // X-constant plane, use Y/Z as UV
                    u = positions[base + 1];
                    v = positions[base + 2];
                }
                case 1 -> { // Y-constant plane, use X/Z
                    u = positions[base];
                    v = positions[base + 2];
                }
                case 2 -> { // Z-constant plane, use X/Y
                    u = positions[base];
                    v = positions[base + 1];
                }
                default -> {
                    return info.reset(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, minX, maxX, minZ, maxZ, originalIndex, posHash);
                }
            }
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }
        return info.reset(spriteKey, normal, axis, plane, minU, maxU, minV, maxV, minX, maxX, minZ, maxZ, originalIndex, posHash);
    }
    private int dominantAxis(float[] normal) {
        if (normal == null || normal.length < 3) return -1;
        float ax = Math.abs(normal[0]);
        float ay = Math.abs(normal[1]);
        float az = Math.abs(normal[2]);
        int axis;
        float max = ax;
        axis = 0;
        if (ay > max) { max = ay; axis = 1; }
        if (az > max) { max = az; axis = 2; }
        if (max < AXIS_NORMAL_DOT_MIN) return -1;
        // Ensure off-axis components are tiny to avoid diagonal faces
        if (axis != 0 && ax > AXIS_COMPONENT_EPS) return -1;
        if (axis != 1 && ay > AXIS_COMPONENT_EPS) return -1;
        if (axis != 2 && az > AXIS_COMPONENT_EPS) return -1;
        return axis;
    }
    /**
     * Apply overlay offset in LOCAL coordinate system (0-1 range).
     * This avoids float precision loss in large scenes by operating on small values.
     *
     * @param localPositions Local coordinates (0-1 range) of the quad vertices
     * @param overlayIndex Index of this overlay layer (0-based)
     */
    private void applyLocalOverlayOffset(float[] localPositions, int overlayIndex) {
        if (localPositions == null || localPositions.length < 12) return;

        // Calculate quad center in local coordinate system (0-1 range)
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < 4; i++) {
            cx += localPositions[i * 3];
            cy += localPositions[i * 3 + 1];
            cz += localPositions[i * 3 + 2];
        }
        cx *= 0.25f; cy *= 0.25f; cz *= 0.25f;

        // Block center in local coordinates is at (0.5, 0.5, 0.5)
        final float localCenter = 0.5f;
        float dx = cx - localCenter;
        float dy = cy - localCenter;
        float dz = cz - localCenter;

        // Find dominant axis to determine which face the quad is on
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        float adz = Math.abs(dz);
        float nx, ny, nz;
        if (adx >= ady && adx >= adz) {
            // X-axis face (East/West)
            nx = dx > 0 ? 1f : -1f;
            ny = 0f;
            nz = 0f;
        } else if (ady >= adx && ady >= adz) {
            // Y-axis face (Top/Bottom)
            nx = 0f;
            ny = dy > 0 ? 1f : -1f;
            nz = 0f;
        } else {
            // Z-axis face (North/South)
            nx = 0f;
            ny = 0f;
            nz = dz > 0 ? 1f : -1f;
        }

        // Apply progressive offset based on overlay index
        // overlayIndex=0 -> 1x offset, overlayIndex=1 -> 2x offset, etc.
        float offsetMultiplier = (overlayIndex + 1);
        float offset = OVERLAY_ZFIGHT_OFFSET * offsetMultiplier;

        // Apply offset in local coordinate system - much better precision since values are small (0-1)
        for (int i = 0; i < 4; i++) {
            localPositions[i * 3]     += nx * offset;
            localPositions[i * 3 + 1] += ny * offset;
            localPositions[i * 3 + 2] += nz * offset;
        }
    }

    /**
     * Check if a quad with given position hash and sprite key is cached as an overlay.
     * Used to skip rendering overlay quads in PASS 2 (they'll be output after their base quad).
     */
    // ===== LEGACY POSITION-HASH OVERLAY LOOKUP METHODS (REMOVED) =====
    // These methods are no longer needed with the new baseMaterialKey-based architecture
    /*
    private boolean isQuadCachedAsOverlay(long posHash, String spriteKey) {
        if (overlayOriginalHashes.contains(posHash)) return true;
        // Check vanilla overlays
        List<OverlayQuadData> vanillaList = overlayCacheVanilla.get(posHash);
        if (vanillaList != null) {
            for (OverlayQuadData data : vanillaList) {
                if (data.spriteKey.equals(spriteKey)) return true;
            }
        }
        // Check CTM overlays
        List<OverlayQuadData> ctmList = overlayCacheCTM.get(posHash);
        if (ctmList != null) {
            for (OverlayQuadData data : ctmList) {
                if (data.spriteKey.equals(spriteKey)) return true;
            }
        }
        return false;
    }

    private OverlayQuadData findOverlayForSprite(long posHash, BlockPos pos, String spriteKey) {
        // Search in vanilla overlays first
        List<OverlayQuadData> vanillaList = overlayCacheVanilla.get(posHash);
        if (vanillaList != null) {
            for (OverlayQuadData data : vanillaList) {
                if (data.spriteKey.equals(spriteKey)) return data;
            }
        }
        // Then search in CTM overlays
        List<OverlayQuadData> ctmList = overlayCacheCTM.get(posHash);
        if (ctmList != null) {
            for (OverlayQuadData data : ctmList) {
                if (data.spriteKey.equals(spriteKey)) return data;
            }
        }
        return null;
    }
    private List<OverlayQuadData> findOverlaysForBase(long posHash, BlockPos pos) {
        // Base UV should only match overlays at the same geometric position to avoid misapplication (e.g., side overlay affecting top face)
        List<OverlayQuadData> overlays = overlayCacheCombined.get(posHash);
        return (overlays != null) ? overlays : new ArrayList<>();
    }
    */

    private boolean isLikelyCTMOverlay(String spriteKey) {
        if (spriteKey == null) return false;
        String key = spriteKey.toLowerCase(Locale.ROOT);
        return key.matches(".*_\\d+$")
            || key.contains("/ctm/")
            || key.contains("ctm/");
    }
    private boolean isCTMQuad(String spriteKey) {
        return spriteKey != null && spriteKey.contains("continuity");
    }
    private boolean isVanillaOverlayQuad(String spriteKey) {
        return spriteKey != null && spriteKey.contains("_overlay");
    }

    /**
     * Extract base material key from vanilla overlay sprite name.
     * Example: "minecraft:block/grass_block_overlay" -> "minecraft:grass_block"
     */
    private String extractVanillaOverlayBase(String spriteKey) {
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
    private CtmOverlayInfo resolveCtmOverlay(TextureAtlasSprite sprite, String spriteKey, Object model) {
        if (sprite == null) return null;
        ResourceLocation name = sprite.contents().name();
        String cacheKey = name.toString();
        if (ctmOverlayCache.containsKey(cacheKey)) {
            return ctmOverlayCache.get(cacheKey);
        }
        CtmOverlayInfo result = null;
        // First, try Continuity reflection (QuadProcessors -> overlay processors)
        CtmOverlayInfo reflectedProcessor = tryReflectContinuityProcessors(name);
        if (reflectedProcessor != null) {
            logCtmDebug("[CTM][REFLECT] Using Continuity processor map for " + name);
            ctmOverlayCache.put(cacheKey, reflectedProcessor);
            return reflectedProcessor;
        }
        // Try to reflect properties from CtmBakedModel
        CtmOverlayInfo reflected = tryReflectCtm(model, name, spriteKey);
        if (reflected != null) {
            logCtmDebug("[CTM][REFLECT] Using reflected properties for " + name);
            ctmOverlayCache.put(cacheKey, reflected);
            return reflected;
        }
        // Heuristic fallback for Continuity reserved sprites: treat as overlay to keep offset
        if (result == null && name.getPath().contains("continuity_reserved")) {
            logCtmDebug("[CTM] Heuristic overlay for continuity_reserved sprite " + name);
            result = new CtmOverlayInfo(true, null, null, parseTileIndex(name) >= 0 ? parseTileIndex(name) : null);
        }
        ctmOverlayCache.put(cacheKey, result);
        return result;
    }
    private int parseTileIndex(ResourceLocation spriteName) {
        String file = spriteName.getPath();
        int idx = file.lastIndexOf('/');
        if (idx >= 0) file = file.substring(idx + 1);
        try {
            return Integer.parseInt(file.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }
    private boolean matchesTile(String tiles, int tileIndex) {
        if (tileIndex < 0) return true;
        String[] parts = tiles.split("[ ,]");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] range = p.split("-");
                try {
                    int start = Integer.parseInt(range[0]);
                    int end = Integer.parseInt(range[1]);
                    if (tileIndex >= start && tileIndex <= end) return true;
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    if (tileIndex == Integer.parseInt(p)) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }
    private CtmOverlayInfo tryReflectCtm(Object model, ResourceLocation spriteName, String spriteKey) {
        if (model == null) return null;
        Class<?> cls = model.getClass();
        if (!cls.getName().toLowerCase(Locale.ROOT).contains("continuity")) return null;
        logCtmDebug("[CTM][REFLECT] Inspecting model class " + cls.getName() + " for sprite " + spriteName);
        Properties props = null;
        // Scan declared fields for properties-like object
        for (var field : cls.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(model);
                if (value == null) continue;
                String vCls = value.getClass().getName();
                logCtmDebug("[CTM][REFLECT] Field " + field.getName() + " -> " + vCls);
                if (value instanceof Properties p) {
                    props = p;
                    break;
                }
                if (vCls.toLowerCase(Locale.ROOT).contains("ctmproperties") || vCls.toLowerCase(Locale.ROOT).contains("properties")) {
                    props = extractPropertiesFromObject(value);
                    logCtmDebug("[CTM][REFLECT] Extracted properties from field " + field.getName());
                    break;
                }
            } catch (Throwable t) {
                logCtmDebug("[CTM][REFLECT][WARN] Failed reading field " + field.getName() + ": " + t.getMessage());
            }
        }
        if (props == null) return null;
        String method = props.getProperty("method", "").trim().toLowerCase(Locale.ROOT);
        String tiles = props.getProperty("tiles", "").trim();
        String connectTiles = props.getProperty("connectTiles", "").trim();
        int tileIndex = parseTileIndex(spriteName);
        boolean tileMatch = matchesTile(tiles, tileIndex);
        boolean isOverlay = "overlay".equals(method) && tileMatch;
        String baseMaterialKey = connectTiles.isEmpty() ? null : (connectTiles.contains(":") ? connectTiles : "minecraft:" + connectTiles);
        logCtmDebug("[CTM][REFLECT] method=" + method + " tiles=" + tiles + " connectTiles=" + connectTiles + " tileIdx=" + tileIndex + " match=" + tileMatch);
        if (isOverlay) {
            return new CtmOverlayInfo(true, baseMaterialKey, "reflected:" + cls.getName(), tileIndex >= 0 ? tileIndex : null);
        }
        return null;
    }
    @SuppressWarnings("unchecked")
    private CtmOverlayInfo tryReflectContinuityProcessors(ResourceLocation spriteName) {
        // Build the lookup map once
        // Allow one retry if previously scanned empty (e.g., before resource reload)
        if (continuityOverlayScanned && continuityOverlayCache.isEmpty() && continuityOverlayRetryAllowed) {
            continuityOverlayRetryAllowed = false;
            continuityOverlayScanned = false;
        }
        if (!continuityOverlayScanned) {
            continuityOverlayScanned = true;
            try {
                Class<?> qpClass = Class.forName("me.pepperbell.continuity.client.model.QuadProcessors");
                var holdersField = qpClass.getDeclaredField("processorHolders");
                holdersField.setAccessible(true);
                Object holdersObj = holdersField.get(null);
                if (holdersObj instanceof Object[] holdersArr) {
                    for (Object holder : holdersArr) {
                        if (holder == null) continue;
                        Object processor = null;
                        try {
                            var m = holder.getClass().getMethod("processor");
                            processor = m.invoke(holder);
                        } catch (Throwable ignored) {}
                        if (processor == null) continue;
                        Class<?> pCls = processor.getClass();
                        String name = pCls.getName().toLowerCase(Locale.ROOT);
                        if (!name.contains("overlay")) continue;
                        // Try to read fields from StandardOverlayQuadProcessor (and compatibles)
                        Object[] sprites = null;
                        Set<?> connectTiles = null;
                        Set<?> matchTiles = null;
                        try { var f = pCls.getDeclaredField("sprites"); f.setAccessible(true); sprites = (Object[]) f.get(processor); } catch (Throwable ignored) {}
                        try { var f = pCls.getDeclaredField("connectTilesSet"); f.setAccessible(true); connectTiles = (Set<?>) f.get(processor); } catch (Throwable ignored) {}
                        try { var f = pCls.getDeclaredField("matchTilesSet"); f.setAccessible(true); matchTiles = (Set<?>) f.get(processor); } catch (Throwable ignored) {}
                        String baseMaterialKey = null;
                        baseMaterialKey = firstIdentifier(connectTiles);
                        if (baseMaterialKey == null) baseMaterialKey = firstIdentifier(matchTiles);
                        if (sprites != null) {
                            for (Object s : sprites) {
                                String key = spriteNameFromObject(s);
                                if (key == null) continue;
                                continuityOverlayCache.put(key, new CtmOverlayInfo(true, baseMaterialKey, "reflect:processor:" + pCls.getName(), null));
                            }
                        }
                    }
                }
                if (CTM_LOG_ENABLED && ctmDebugLog != null && !continuityOverlayCache.isEmpty()) {
                    ctmDebugLog.println("[CTM][REFLECT] Continuity processor scan complete, entries=" + continuityOverlayCache.size());
                    ctmDebugLog.flush();
                }
            } catch (Throwable t) {
                if (CTM_LOG_ENABLED && ctmDebugLog != null) {
                    ctmDebugLog.println("[CTM][REFLECT][WARN] Continuity processor scan failed: " + t.getMessage());
                    ctmDebugLog.flush();
                }
            }
        }
        if (continuityOverlayCache.isEmpty()) return null;
        return continuityOverlayCache.get(spriteName.toString());
    }
    private String firstIdentifier(Set<?> set) {
        if (set == null || set.isEmpty()) return null;
        Object first = set.iterator().next();
        if (first == null) return null;
        String s = first.toString();
        // Normalize missing namespace
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        return s;
    }
    private String deriveBaseFromSprite(String spriteKey) {
        if (spriteKey == null || spriteKey.isEmpty()) return null;
        String key = spriteKey;
        String namespace = "minecraft";
        String path = key;
        int colon = key.indexOf(':');
        if (colon >= 0) {
            namespace = key.substring(0, colon);
            path = key.substring(colon + 1);
        }
        if (path.startsWith("block/")) {
            path = path.substring("block/".length());
        }
        if (path.contains("continuity_reserved")) {
            return null; // avoid reserved names as base
        }
        // strip overlay and directional suffixes
        path = path.replace("_overlay", "").replace("_top", "").replace("_side", "");
        // strip trailing digits
        path = path.replaceAll("_\\d+$", "");
        return namespace + ":" + path;
    }
    private String spriteNameFromObject(Object spriteObj) {
        if (spriteObj == null) return null;
        try {
            var getContents = spriteObj.getClass().getMethod("getContents");
            Object contents = getContents.invoke(spriteObj);
            if (contents != null) {
                try {
                    var getId = contents.getClass().getMethod("getId");
                    Object id = getId.invoke(contents);
                    return (id != null) ? id.toString() : null;
                } catch (NoSuchMethodException ignored) {
                    try {
                        var nameField = contents.getClass().getDeclaredField("id");
                        nameField.setAccessible(true);
                        Object id = nameField.get(contents);
                        return (id != null) ? id.toString() : null;
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}
        try {
            var contentsField = spriteObj.getClass().getDeclaredField("contents");
            contentsField.setAccessible(true);
            Object contents = contentsField.get(spriteObj);
            if (contents != null) {
                var getId = contents.getClass().getMethod("getId");
                Object id = getId.invoke(contents);
                return (id != null) ? id.toString() : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private Properties extractPropertiesFromObject(Object obj) {
        Properties p = new Properties();
        readProperty(obj, "method", p);
        readProperty(obj, "tiles", p);
        readProperty(obj, "connectTiles", p);
        return p;
    }
    private void readProperty(Object obj, String name, Properties p) {
        try {
            var cls = obj.getClass();
            // Field
            try {
                var f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) p.setProperty(name, v.toString());
                return;
            } catch (NoSuchFieldException ignored) {}
            // Getter
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                var m = cls.getDeclaredMethod(getter);
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (v != null) p.setProperty(name, v.toString());
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            logCtmDebug("[CTM][REFLECT][WARN] readProperty " + name + " failed: " + t.getMessage());
        }
    }
    private boolean isApprox1x1Square(QuadInfo info) {
        if (info == null || info.axis < 0) return false;
        float sizeU = info.maxU - info.minU;
        float sizeV = info.maxV - info.minV;
        // Relaxed tolerance for floating point errors (0.01 instead of 0.001)
        float SIZE_TOLERANCE = 0.01f;
        // Must be approximately square
        if (Math.abs(sizeU - sizeV) > SIZE_TOLERANCE) return false;
        // Must be approximately 1.0 unit (±0.01 tolerance)
        if (Math.abs(sizeU - UNIT_SIZE) > SIZE_TOLERANCE) return false;
        return true;
    }

    /**
     * Cache an overlay quad organized by its base material key (source block).
     * This replaces the old position-hash based caching to ensure overlays are
     * attributed to their true source block, not where they "land".
     */
    private void cacheOverlayByMaterial(String baseMaterialKey, BlockState state, BlockPos pos, BakedQuad quad, Vec3 randomOffset, String spriteKey) {
        if (baseMaterialKey == null || baseMaterialKey.isEmpty()) {
            baseMaterialKey = "unknown";
        }

        // Mark this sprite as processed to skip it in PASS 2
        processedOverlaySprites.add(spriteKey);

        var sprite = quad.getSprite();
        if (sprite == null) return;

        // Extract direction early for visibility culling
        Direction dir = quad.getDirection();

        // Register and cache dynamic overlay textures
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
        float dv = v1 - v0;
        if (du == 0) du = 1f;
        if (dv == 0) dv = 1f;
        int[] vertexColors = null;
        float[] localPos = null;
        try {
            int[] verts;
            try {
                verts = quad.getVertices();
            } catch (Throwable t) { return; }
            if (verts.length < 32) return;
            final int stride = 8;
            vertexColors = int4Pool.acquire();
            localPos = positions12Pool.acquire();
            // Extract local coordinates and UV/colors
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
            applyLocalOverlayOffset(localPos, overlayIndex);

            // Convert to world coordinates with offset applied
            for (int i = 0; i < 4; i++) {
                double worldX = pos.getX() + localPos[i * 3] + offsetX + (randomOffset != null ? randomOffset.x : 0);
                double worldY = pos.getY() + localPos[i * 3 + 1] + offsetY + (randomOffset != null ? randomOffset.y : 0);
                double worldZ = pos.getZ() + localPos[i * 3 + 2] + offsetZ + (randomOffset != null ? randomOffset.z : 0);
                positions[i * 3] = (float)worldX;
                positions[i * 3 + 1] = (float)worldY;
                positions[i * 3 + 2] = (float)worldZ;
            }

            int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
            float[] overlayColorUv = new float[8];
            var placement = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, overlayColor);
            overlayColorUv[0] = placement.u0(); overlayColorUv[1] = placement.v0();
            overlayColorUv[2] = placement.u1(); overlayColorUv[3] = placement.v0();
            overlayColorUv[4] = placement.u1(); overlayColorUv[5] = placement.v1();
            overlayColorUv[6] = placement.u0(); overlayColorUv[7] = placement.v1();

            float[] normal = GeometryUtil.computeFaceNormal(positions);
            boolean emissive = state.getLightEmission() > 0;

            OverlayQuadData data = new OverlayQuadData(positions.clone(), normal, uv0.clone(), overlayColorUv, spriteKey, overlayColor, true, overlayIndex, baseMaterialKey, emissive, dir);
            overlayList.add(data);
        } finally {
            if (vertexColors != null) int4Pool.release(vertexColors);
            if (localPos != null) positions12Pool.release(localPos);
            positions12Pool.release(positions);
            uv8Pool.release(uv0);
        }
    }

    // ===== LEGACY POSITION-HASH BASED OVERLAY CACHING (REMOVED) =====
    // These methods have been replaced by cacheOverlayByMaterial()
    /*
    private void cacheOverlayQuad(BlockState state, BlockPos pos, BakedQuad quad, boolean isCtmOverlay, int overlayIndex, Vec3 randomOffset, String baseMaterialKey) {
        cacheOverlayQuadInternal(state, pos, quad, isCtmOverlay, overlayIndex, randomOffset, null, false, baseMaterialKey);
    }
    private void cacheOverlayQuadWithBaseHash(BlockState state, BlockPos pos, BakedQuad quad, boolean isCtmOverlay, int overlayIndex, Vec3 randomOffset, Long forcedBaseHash, String baseMaterialKey) {
        cacheOverlayQuadInternal(state, pos, quad, isCtmOverlay, overlayIndex, randomOffset, forcedBaseHash, true, baseMaterialKey);
    }
    private void cacheOverlayQuadInternal(BlockState state, BlockPos pos, BakedQuad quad, boolean isCtmOverlay, int overlayIndex, Vec3 randomOffset, Long forcedBaseHash, boolean recordOriginal, String baseMaterialKey) {
        var sprite = quad.getSprite();
        if (sprite == null) return;
        String spriteKey = SpriteKeyResolver.resolve(sprite);
        // Identify dynamic overlay textures (e.g. CTM overlay variants)
        boolean isDynamicTexture = spriteKey.contains("_overlay")
            || spriteKey.matches(".*\\d+$")
            || !ctx.getMaterialPaths().containsKey(spriteKey);
        // Register overlay texture FIRST to ensure it gets added to atlasBook
        if (isDynamicTexture) {
            com.voxelbridge.export.texture.TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
        }
        // Then capture image if not already cached
        if (isDynamicTexture && ctx.getCachedSpriteImage(spriteKey) == null) {
            try {
                BufferedImage image = TextureLoader.fromSprite(sprite);
                if (image != null) {
                    ctx.cacheSpriteImage(spriteKey, image);
                }
            } catch (Exception ignore) {}
        }
        float[] positions = positions12Pool.acquire();
        float[] uv0 = uv8Pool.acquire();
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // Detect animated texture and adjust v1 to first frame (same as extractVertices)
        int spriteWidth = sprite.contents().width();
        int spriteHeight = sprite.contents().height();
        if (spriteHeight > spriteWidth) {
            int frameCount = spriteHeight / spriteWidth;
            float frameRatio = 1.0f / frameCount;
            v1 = v0 + (v1 - v0) * frameRatio;
        }
        float du = u1 - u0;
        float dv = v1 - v0;
        if (du == 0) du = 1f;
        if (dv == 0) dv = 1f;
        int[] vertexColors = null;
        float[] localPos = null;
        float[] rawPos = null;
        try {
            int[] verts;
            try {
                verts = quad.getVertices();
            } catch (Throwable t) { return; }
            if (verts.length < 32) return;
            final int stride = 8;
            vertexColors = int4Pool.acquire();
            // First pass: extract local coordinates and UV/colors
            localPos = positions12Pool.acquire();
            rawPos = positions12Pool.acquire();
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

            // Convert raw positions for skip detection before offset
            for (int i = 0; i < 4; i++) {
                double worldX = pos.getX() + localPos[i * 3] + offsetX + (randomOffset != null ? randomOffset.x : 0);
                double worldY = pos.getY() + localPos[i * 3 + 1] + offsetY + (randomOffset != null ? randomOffset.y : 0);
                double worldZ = pos.getZ() + localPos[i * 3 + 2] + offsetZ + (randomOffset != null ? randomOffset.z : 0);
                rawPos[i * 3] = (float)worldX;
                rawPos[i * 3 + 1] = (float)worldY;
                rawPos[i * 3 + 2] = (float)worldZ;
            }
            long rawHash = computePositionHash(rawPos);

            // Convert to world coordinates with double precision to avoid precision loss
            for (int i = 0; i < 4; i++) {
                double worldX = pos.getX() + localPos[i * 3] + offsetX + (randomOffset != null ? randomOffset.x : 0);
                double worldY = pos.getY() + localPos[i * 3 + 1] + offsetY + (randomOffset != null ? randomOffset.y : 0);
                double worldZ = pos.getZ() + localPos[i * 3 + 2] + offsetZ + (randomOffset != null ? randomOffset.z : 0);
                positions[i * 3] = (float)worldX;
                positions[i * 3 + 1] = (float)worldY;
                positions[i * 3 + 2] = (float)worldZ;
            }
            int overlayColor = extractOverlayColor(state, pos, quad, vertexColors);
            float[] overlayColorUv = new float[8];
            var placement = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, overlayColor);
            overlayColorUv[0] = placement.u0(); overlayColorUv[1] = placement.v0();
            overlayColorUv[2] = placement.u1(); overlayColorUv[3] = placement.v0();
            overlayColorUv[4] = placement.u1(); overlayColorUv[5] = placement.v1();
            overlayColorUv[6] = placement.u0(); overlayColorUv[7] = placement.v1();

            // Calculate normal for the overlay quad
            float[] normal = GeometryUtil.computeFaceNormal(positions);

            if (recordOriginal) {
                overlayOriginalHashes.add(rawHash);
            }
            long posHash = (forcedBaseHash != null) ? forcedBaseHash : rawHash;
            Map<Long, List<OverlayQuadData>> cache = isCtmOverlay ? overlayCacheCTM : overlayCacheVanilla;
            List<OverlayQuadData> overlayList = cache.computeIfAbsent(posHash, k -> new ArrayList<>());
            int resolvedOverlayIndex = overlayList.size();
            // Apply overlay offset using resolved index to keep layers sequential
            applyLocalOverlayOffset(localPos, resolvedOverlayIndex);
            // Recompute positions with offset applied
            for (int i = 0; i < 4; i++) {
                double worldX = pos.getX() + localPos[i * 3] + offsetX + (randomOffset != null ? randomOffset.x : 0);
                double worldY = pos.getY() + localPos[i * 3 + 1] + offsetY + (randomOffset != null ? randomOffset.y : 0);
                double worldZ = pos.getZ() + localPos[i * 3 + 2] + offsetZ + (randomOffset != null ? randomOffset.z : 0);
                positions[i * 3] = (float)worldX;
                positions[i * 3 + 1] = (float)worldY;
                positions[i * 3 + 2] = (float)worldZ;
            }
            String resolvedBaseMaterial = (baseMaterialKey != null && !baseMaterialKey.isEmpty()) ? baseMaterialKey : deriveBaseFromSprite(spriteKey);
            OverlayQuadData data = new OverlayQuadData(positions.clone(), normal, uv0.clone(), overlayColorUv, spriteKey, overlayColor, isCtmOverlay, resolvedOverlayIndex, resolvedBaseMaterial, state.getLightEmission() > 0);
            overlayList.add(data);
        } finally {
            if (vertexColors != null) int4Pool.release(vertexColors);
            if (localPos != null) positions12Pool.release(localPos);
            if (rawPos != null) positions12Pool.release(rawPos);
            positions12Pool.release(positions);
            uv8Pool.release(uv0);
        }
    }
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
    private ModelData getModelData(BakedModel model, BlockState state, BlockPos pos) {
        ModelData modelData = ModelData.EMPTY;
        try { modelData = level.getModelData(pos); } catch (Throwable ignored) {}
        try {
            if (model instanceof IBakedModelExtension extension) {
                modelData = extension.getModelData(level, pos, state, modelData);
            }
        } catch (Throwable ignored) {}
        return modelData;
    }
    private List<BakedQuad> getQuads(BakedModel model, BlockState state, ModelData data, BlockPos pos) {
        List<BakedQuad> quads = new ArrayList<>();
        long seed = state.is(Blocks.LILY_PAD) ? computeBushSeed(pos) : Mth.getSeed(pos.getX(), pos.getY(), pos.getZ());
        RandomSource rand = RandomSource.create(seed);
        try {
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer != null && model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter()) {
                // Use Fabric Rendering API for CTM support
                final LinkedList<MeshBuilder> builders = new LinkedList<>();
                final LinkedList<QuadEmitter> emitters = new LinkedList<>();
                final LinkedList<RenderContext.QuadTransform> transforms = new LinkedList<>();
                List<BakedQuad> fabricQuads = new ArrayList<>();
                // Init base layer
                MeshBuilder baseBuilder = renderer.meshBuilder();
                builders.push(baseBuilder);
                emitters.push(baseBuilder.getEmitter());
                transforms.push(null);
                RenderContext context = new RenderContext() {
                    @Override
                    public QuadEmitter getEmitter() {
                        return emitters.peek();
                    }
                    @Override
                    public boolean isFaceCulled(Direction face) {
                        return false;
                    }
                    @Override
                    public void pushTransform(RenderContext.QuadTransform transform) {
                        MeshBuilder layerBuilder = renderer.meshBuilder();
                        builders.push(layerBuilder);
                        emitters.push(layerBuilder.getEmitter());
                        transforms.push(transform);
                        if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                            ctmDebugLog.println("  [TRANSFORM] Push - Stack size: " + emitters.size());
                        }
                    }
                    @Override
                    public void popTransform() {
                        if (emitters.size() <= 1) {
                             if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                                ctmDebugLog.println("  [TRANSFORM] Pop ignored (stack empty or base reached)");
                            }
                            return;
                        }
                        MeshBuilder topBuilder = builders.pop();
                        emitters.pop();
                        RenderContext.QuadTransform transform = transforms.pop();
                        QuadEmitter target = emitters.peek();
                        Mesh mesh = topBuilder.build();
                        mesh.forEach(q -> {
                            target.copyFrom(q);
                            if (transform.transform(target)) {
                                target.emit();
                            }
                        });
                        if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                            ctmDebugLog.println("  [TRANSFORM] Pop - Flushed layer to parent");
                        }
                    }
                    @Override
                    public RenderContext.BakedModelConsumer bakedModelConsumer() {
                        RenderContext current = this;
                        return new RenderContext.BakedModelConsumer() {
                            @Override
                            public void accept(BakedModel bakedModel) {
                                if (bakedModel instanceof FabricBakedModel fbm) {
                                    fbm.emitBlockQuads(level, state, pos, () -> rand, current);
                                }
                            }
                            @Override
                            public void accept(BakedModel bakedModel, BlockState modelState) {
                                if (bakedModel instanceof FabricBakedModel fbm) {
                                    BlockState targetState = (modelState != null) ? modelState : state;
                                    fbm.emitBlockQuads(level, targetState, pos, () -> rand, current);
                                }
                            }
                        };
                    }
                };
                fabricModel.emitBlockQuads(level, state, pos, () -> rand, context);
                Mesh mesh = baseBuilder.build();
                mesh.forEach(q -> fabricQuads.add(toBakedQuad(q)));
                if (!fabricQuads.isEmpty()) {
                    if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                        try {
                            ctmDebugLog.println("  [FABRIC API PATH] Collected " + fabricQuads.size() + " quads via Fabric Renderer");
                            ctmDebugLog.flush();
                        } catch (Exception ignored) {}
                    }
                    return fabricQuads;
                }
            }
        } catch (Throwable t) {
            if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                try {
                    ctmDebugLog.println("  [FABRIC API ERROR] " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    t.printStackTrace(ctmDebugLog);
                    ctmDebugLog.flush();
                } catch (Exception ignored) {}
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
            if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                try {
                    ctmDebugLog.println("  [VANILLA PATH] Collected " + quads.size() + " quads via vanilla getQuads()");
                    ctmDebugLog.flush();
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return quads;
    }
    private BakedQuad toBakedQuad(QuadView quad) {
        int vertexSize = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
        int[] vertices = new int[vertexSize * 4];
        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;
            vertices[offset + 0] = Float.floatToRawIntBits(quad.x(i));
            vertices[offset + 1] = Float.floatToRawIntBits(quad.y(i));
            vertices[offset + 2] = Float.floatToRawIntBits(quad.z(i));
            vertices[offset + 3] = quad.color(i);
            vertices[offset + 4] = Float.floatToRawIntBits(quad.u(i));
            vertices[offset + 5] = Float.floatToRawIntBits(quad.v(i));
            vertices[offset + 6] = quad.lightmap(i);
            if (quad.hasNormal(i)) {
                float nx = quad.normalX(i);
                float ny = quad.normalY(i);
                float nz = quad.normalZ(i);
                vertices[offset + 7] = packNormal(nx, ny, nz);
            } else {
                Direction dir = quad.lightFace();
                if (dir != null) {
                    vertices[offset + 7] = packNormal(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                } else {
                    vertices[offset + 7] = packNormal(0, 1, 0);
                }
            }
        }
        TextureAtlasSprite sprite = spriteFinder.find(quad, 0);
        Direction cullFace = quad.cullFace();
        int tintIndex = quad.colorIndex();
        boolean shade = true;
        return new BakedQuad(vertices, tintIndex, cullFace, sprite, shade);
    }
    private int packNormal(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 0.0001f) {
            x /= len;
            y /= len;
            z /= len;
        }
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }
    /**
     * Pre-load and cache PBR companion textures (normal and specular maps).
     *
     * This method is called during the block texture processing phase to:
     * 1. Discover and cache PBR textures early
     * 2. Avoid missing textures during atlas generation
     * 3. Use enhanced multi-level fallback strategy for finding PBR textures
     *
     * Note: This is the "pre-caching" phase. Actual atlas generation happens in TextureAtlasManager.
     */
    private void ensurePbrTexturesCached(TextureAtlasSprite sprite, String spriteKey) {
        if (sprite == null || spriteKey == null) return;

        // Use PbrTextureHelper's enhanced lookup logic
        // This handles all fallback strategies automatically and caches results in ctx
        com.voxelbridge.export.texture.PbrTextureHelper.ensurePbrCached(ctx, spriteKey, sprite);

        // If PbrTextureHelper couldn't find textures, try Iris PBR reflection as fallback
        String normalKey = spriteKey + "_n";
        String specKey = spriteKey + "_s";

        // Iris path removed; rely on existing PBR loading elsewhere
    }

    // Iris PBR reflection removed (Sodium dependency not supported)
    /**
     * Detects if a model uses CTM/connected textures.
     */
    private boolean isCTMModel(BakedModel model, List<BakedQuad> quads) {
        if (!(model instanceof FabricBakedModel fbm)) {
            return false;
        }
        if (fbm.isVanillaAdapter()) {
            return false;
        }
        String className = model.getClass().getName().toLowerCase();
        boolean classHints = className.contains("continuity") || className.contains("ctm") || className.contains("connected");

        // Check sprite diversity (CTM uses multiple sprite variants)
        Set<String> uniqueSprites = new HashSet<>();
        boolean ctmPathHint = false;
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) continue;
            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
            if (spriteKey.contains("_overlay")) continue;
            String baseSprite = spriteKey.replaceAll("_\\d+$", "");
            uniqueSprites.add(baseSprite);
            // Path-based hints for CTM (even if only one variant exists in this sample)
            String lower = spriteKey.toLowerCase(Locale.ROOT);
            if (lower.contains("/ctm/") || lower.contains("ctm/") || lower.contains("_ctm") || lower.contains("/connected/")) {
                ctmPathHint = true;
            }
        }
        // Loosen detection: if the model class hints CTM OR sprites have CTM-like paths, treat as CTM even with one variant
        if (classHints && (uniqueSprites.size() >= 1)) return true;
        if (uniqueSprites.size() > 1) return true;
        return ctmPathHint;
    }
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
            BlockPos neighbor = mutablePos;
            if (isOutsideRegion(neighbor)) return false;
            if (!isNeighborSolid(neighbor)) return false;
        }
        return true;
    }
    private boolean isFaceOccluded(BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        BlockPos neighbor = mutablePos;
        if (isOutsideRegion(neighbor)) return false;
        return isNeighborSolid(neighbor);
    }
    private boolean isFaceOccludedBySameBlock(BlockState state, BlockPos pos, Direction face) {
        mutablePos.setWithOffset(pos, face);
        BlockPos neighbor = mutablePos;
        if (isOutsideRegion(neighbor)) return false;
        BlockState neighborState = level.getBlockState(neighbor);
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
    private int computeTintColor(BlockState state, BlockPos pos, BakedQuad quad) {
        if (quad.getTintIndex() < 0) return 0xFFFFFFFF;
        int argb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
        return (argb == -1) ? 0xFFFFFFFF : argb;
    }
    private long computePositionHash(float[] positions) {
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> {
            int ia = a * 3; int ib = b * 3;
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
    private long computeQuadKey(String spriteKey, float[] positions, float[] normal, boolean doubleSided, float[] uv0) {
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> {
            int ia = a * 3; int ib = b * 3;
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
    private long computeFootprintHash(QuadInfo info) {
        long hash = 1125899906842597L;
        hash = 31 * hash + Math.round(info.minX * 1000f);
        hash = 31 * hash + Math.round(info.maxX * 1000f);
        hash = 31 * hash + Math.round(info.minZ * 1000f);
        hash = 31 * hash + Math.round(info.maxZ * 1000f);
        return hash;
    }
    private static final class ObjectPool<T> {
        private final ArrayDeque<T> free = new ArrayDeque<>();
        private final int maxSize;
        private final Supplier<T> factory;

        ObjectPool(int maxSize, Supplier<T> factory) {
            this.maxSize = maxSize;
            this.factory = factory;
        }

        T acquire() {
            T value = free.pollFirst();
            return (value != null) ? value : factory.get();
        }

        void release(T value) {
            if (value == null || free.size() >= maxSize) return;
            free.addFirst(value);
        }
    }
    public boolean hadMissingNeighborAndReset() {
        boolean result = missingNeighborDetected;
        missingNeighborDetected = false;
        return result;
    }
}
