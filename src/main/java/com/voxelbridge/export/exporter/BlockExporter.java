package com.voxelbridge.export.exporter;

import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExporter;
import com.voxelbridge.export.exporter.blockentity.BlockEntityExportResult;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.modhandler.ModHandledQuads;
import com.voxelbridge.modhandler.ModHandlerRegistry;
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
    private BlockPos regionMin;
    private BlockPos regionMax;
    private double offsetX = 0;
    private double offsetY = 0;
    private double offsetZ = 0;
    // Cache for overlay quads; keep vanilla and CTM separate to avoid CTM overwriting vanilla overlays
    // Changed to List to support multiple overlays per position (up to 4 per face)
    private final Map<Long, List<OverlayQuadData>> overlayCacheVanilla = new HashMap<>();
    private final Map<Long, List<OverlayQuadData>> overlayCacheCTM = new HashMap<>();
    // Combined overlay view (vanilla + CTM) for UV mapping on base quads
    private final Map<Long, List<OverlayQuadData>> overlayCacheCombined = new HashMap<>();
    private volatile boolean missingNeighborDetected = false;
    // Track if current block uses CTM/connected textures (for face culling decisions)
    private boolean currentBlockIsCTM = false;
    // CTM Debug logging
    private static final boolean CTM_LOG_ENABLED = true;
    private static final float AXIS_NORMAL_DOT_MIN = 0.9999f;
    private static final float AXIS_COMPONENT_EPS = 1e-3f;
    private static final float PLANE_EPS = 1e-4f;
    private static final float SIZE_EPS = 1e-4f;
    private static final float UNIT_SIZE = 1.0f;
    private static final float UNIT_EPS = 1e-3f;
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
        OverlayQuadData(float[] positions, float[] normal, float[] uv, float[] colorUv, String spriteKey, int color, boolean ctmOverlay, int overlayIndex) {
            this.positions = positions;
            this.normal = normal;
            this.uv = uv;
            this.colorUv = colorUv;
            this.spriteKey = spriteKey;
            this.color = color;
            this.ctmOverlay = ctmOverlay;
            this.overlayIndex = overlayIndex;
        }
    }
    private static class QuadInfo {
        final String sprite;
        final float[] normal;
        final int axis; // 0=X,1=Y,2=Z, -1 = not axis-aligned
        final float planeCoord;
        final float minU, maxU, minV, maxV;
        final int originalIndex; // Index in the original quads list (for render order)
        QuadInfo(String sprite, float[] normal, int axis, float planeCoord, float minU, float maxU, float minV, float maxV, int originalIndex) {
            this.sprite = sprite;
            this.normal = normal;
            this.axis = axis;
            this.planeCoord = planeCoord;
            this.minU = minU;
            this.maxU = maxU;
            this.minV = minV;
            this.maxV = maxV;
            this.originalIndex = originalIndex;
        }
    }
    public BlockExporter(ExportContext ctx, SceneSink sceneSink, Level level) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.level = level;
        this.chunkCache = (level instanceof ClientLevel cl) ? cl.getChunkSource() : null;
        this.spriteFinder = SpriteFinder.get(ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS));
        this.vanillaRandomTransformEnabled = ctx.isVanillaRandomTransformEnabled();
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
            missingNeighborDetected = true;
            return;
        }
        overlayCacheVanilla.clear();
        overlayCacheCTM.clear();
        overlayCacheCombined.clear();
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
            BlockEntityExportResult beResult = BlockEntityExporter.export(ctx, level, state, be, pos, sceneSink, offsetX, offsetY, offsetZ);
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
        // PASS 1: Identify and cache all overlays (vanilla + CTM)
        Map<Long, List<QuadInfo>> ctmPositionQuads = new HashMap<>();
        // Collect geometric info for all quads (needed for CTM detection)
        int quadIndex = 0;
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) {
                quadIndex++;
                continue;
            }
            float[] positions = new float[12];
            float[] uv0 = new float[8];
            extractVertices(quad, pos, positions, uv0, quad.getSprite(), randomOffset);
            long posHash = computePositionHash(positions);
            float[] normal = computeFaceNormal(positions);
            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
            QuadInfo info = buildQuadInfo(spriteKey, positions, normal, quadIndex);
            ctmPositionQuads.computeIfAbsent(posHash, k -> new ArrayList<>()).add(info);
            quadIndex++;
        }
        // PASS 1b: Analyze each position and cache overlays (position-based detection)
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
            if (hasCTM) {
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
                            cacheOverlayQuad(state, pos, overlayQuad, true, overlayIdx++, randomOffset);
                        }
                    }
                }
            } else if (hasVanillaOverlay) {
                // Case B: Vanilla overlay - sprite-based detection (no geometry check)
                int overlayIdx = 0;
                for (QuadInfo info : quadsAtPos) {
                    if (isVanillaOverlayQuad(info.sprite)) {
                        BakedQuad overlayQuad = quads.get(info.originalIndex);
                        cacheOverlayQuad(state, pos, overlayQuad, false, overlayIdx++, randomOffset);
                    }
                }
            }
        }
        // Build combined overlay cache (merge vanilla and CTM overlays at same position)
        // Copy all vanilla overlays first
        for (Map.Entry<Long, List<OverlayQuadData>> entry : overlayCacheVanilla.entrySet()) {
            overlayCacheCombined.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        // Append CTM overlays to the same positions (don't replace)
        for (Map.Entry<Long, List<OverlayQuadData>> entry : overlayCacheCTM.entrySet()) {
            List<OverlayQuadData> combined = overlayCacheCombined.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            combined.addAll(entry.getValue());
        }
        // Abnormal overlay count detection: count total overlays across all positions
        int totalOverlayCount = 0;
        for (List<OverlayQuadData> list : overlayCacheCombined.values()) {
            totalOverlayCount += list.size();
        }
        int overlayCount = totalOverlayCount;
        if (overlayCount > 24) {
            if (CTM_LOG_ENABLED && ctmDebugLog != null && sampledBlockCount <= 100) {
                ctmDebugLog.println("  [WARNING] Abnormal overlay count: " + overlayCount + " (expected <=24), clearing overlay detection");
                ctmDebugLog.println("  [INFO] This block likely has complex geometry, not CTM overlays");
                ctmDebugLog.flush();
            }
            // Clear all overlay caches to treat this as a normal block without overlays
            overlayCacheVanilla.clear();
            overlayCacheCTM.clear();
            overlayCacheCombined.clear();
        }
        Set<Long> quadKeys = new HashSet<>();
        Set<Long> processedPositions = new HashSet<>(); // Track base quad positions for overlay detection
        // PASS 2: Process all quads and output geometry
        for (BakedQuad quad : quads) {
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
            processQuad(state, pos, quad, quadKeys, processedPositions, ctmPositionQuads, blockKey, randomOffset);
        }
    }
    private void processQuad(BlockState state, BlockPos pos, BakedQuad quad, Set<Long> quadKeys, Set<Long> processedPositions, Map<Long, List<QuadInfo>> ctmPositionQuads, String blockKey, Vec3 randomOffset) {
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) return;
        String spriteKey = SpriteKeyResolver.resolve(sprite);
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
        float[] normal = computeFaceNormal(positions);
        long quadKey = computeQuadKey(spriteKey, positions, normal, doubleSided, uv0);
        if (!quadKeys.add(quadKey)) return;

        // Check if this quad is an overlay (already cached in PASS 1)
        long posHash = computePositionHash(positions);
        if (isQuadCachedAsOverlay(posHash, spriteKey)) {
            // Skip this quad - it's an overlay that will be output after its base quad
            return;
        }

        // This is a base quad - output it
        int argb = computeTintColor(state, pos, quad);
        float[] uv1 = getColormapUV(argb);
        float[] colors = whiteColor();
        sceneSink.addQuad(blockKey, spriteKey, null, positions, uv0, uv1, normal, colors, doubleSided);

        // Check if we've already processed overlays for this position
        if (!processedPositions.add(posHash)) {
            return; // Already output overlays for this position
        }

        // Output all cached overlay quads for this position
        List<OverlayQuadData> overlays = overlayCacheCombined.get(posHash);
        if (overlays != null && !overlays.isEmpty()) {
            for (OverlayQuadData overlay : overlays) {
                sceneSink.addQuad(blockKey, overlay.spriteKey, "overlay", overlay.positions, overlay.uv, overlay.colorUv, overlay.normal, colors, doubleSided);
            }
        }
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
    private float[] getColormapUV(int argb) {
        var p = com.voxelbridge.export.texture.ColorMapManager.registerColor(ctx, argb);
        return new float[] { p.u0(), p.v0(), p.u1(), p.v0(), p.u1(), p.v1(), p.u0(), p.v1() };
    }
    private QuadInfo buildQuadInfo(String spriteKey, float[] positions, float[] normal, int originalIndex) {
        int axis = dominantAxis(normal);
        if (axis < 0) {
            return new QuadInfo(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, originalIndex);
        }
        float plane = positions[axis];
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            int base = i * 3;
            float coord = positions[base + axis];
            if (Math.abs(coord - plane) > PLANE_EPS) {
                return new QuadInfo(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, originalIndex);
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
                    return new QuadInfo(spriteKey, normal, -1, 0f, 0f, 0f, 0f, 0f, originalIndex);
                }
            }
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }
        return new QuadInfo(spriteKey, normal, axis, plane, minU, maxU, minV, maxV, originalIndex);
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
    private boolean isOverlayPair(QuadInfo a, QuadInfo b) {
        if (a == null || b == null) return false;
        if (a.axis < 0 || b.axis < 0) return false;
        if (a.axis != b.axis) return false;
        if (Math.abs(a.planeCoord - b.planeCoord) > PLANE_EPS) return false;
        float sizeU1 = a.maxU - a.minU;
        float sizeU2 = b.maxU - b.minU;
        float sizeV1 = a.maxV - a.minV;
        float sizeV2 = b.maxV - b.minV;
        if (Math.abs(sizeU1 - sizeU2) > SIZE_EPS) return false;
        if (Math.abs(sizeV1 - sizeV2) > SIZE_EPS) return false;
        if (Math.abs(a.minU - b.minU) > SIZE_EPS) return false;
        if (Math.abs(a.minV - b.minV) > SIZE_EPS) return false;
        // Require square of roughly one block in size
        if (Math.abs(sizeU1 - sizeV1) > SIZE_EPS) return false;
        if (Math.abs(sizeU1 - UNIT_SIZE) > UNIT_EPS) return false;
        return true;
    }
    private boolean isOverlayGeometry(float[] positions, float[] normal) {
        QuadInfo info = buildQuadInfo("overlay_check", positions, normal, -1);
        if (info.axis < 0) return false;
        float sizeU = info.maxU - info.minU;
        float sizeV = info.maxV - info.minV;
        if (Math.abs(sizeU - sizeV) > SIZE_EPS) return false;
        if (Math.abs(sizeU - UNIT_SIZE) > UNIT_EPS) return false;
        return true;
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
     * @deprecated Use applyLocalOverlayOffset instead for better precision.
     * This method operates on world coordinates and suffers from float precision loss in large scenes.
     */
    @Deprecated
    private void applyOverlayOffsetWithIndex(BlockPos pos, float[] positions, float[] normal, int overlayIndex) {
        if (positions == null || positions.length < 12 || normal == null || normal.length < 3) return;
        // Determine offset direction based on quad position on block face (not normal direction)
        // This is more reliable as it doesn't depend on potentially incorrect normals
        // Calculate quad center
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < 4; i++) {
            cx += positions[i * 3];
            cy += positions[i * 3 + 1];
            cz += positions[i * 3 + 2];
        }
        cx *= 0.25f; cy *= 0.25f; cz *= 0.25f;
        // Block center in world coordinates
        float centerX = (float) (pos.getX() + 0.5 + offsetX);
        float centerY = (float) (pos.getY() + 0.5 + offsetY);
        float centerZ = (float) (pos.getZ() + 0.5 + offsetZ);
        // Determine which face the quad is on by checking distance from block center
        // Overlay should always offset AWAY from block center
        float dx = cx - centerX;
        float dy = cy - centerY;
        float dz = cz - centerZ;
        // Find dominant axis (which face: X/Y/Z)
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
        // Apply progressive offset based on overlay index (index + 1) to avoid z-fighting
        // overlayIndex=0 -> 1x offset, overlayIndex=1 -> 2x offset, etc.
        float offsetMultiplier = (overlayIndex + 1);
        float offset = OVERLAY_ZFIGHT_OFFSET * offsetMultiplier;
        for (int i = 0; i < 4; i++) {
            positions[i * 3]     += nx * offset;
            positions[i * 3 + 1] += ny * offset;
            positions[i * 3 + 2] += nz * offset;
        }
        normal[0] = nx; normal[1] = ny; normal[2] = nz;
    }
    /**
     * Check if a quad with given position hash and sprite key is cached as an overlay.
     * Used to skip rendering overlay quads in PASS 2 (they'll be output after their base quad).
     */
    private boolean isQuadCachedAsOverlay(long posHash, String spriteKey) {
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
    private void cacheOverlayQuad(BlockState state, BlockPos pos, BakedQuad quad, boolean isCtmOverlay, int overlayIndex, Vec3 randomOffset) {
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
        float[] positions = new float[12];
        float[] uv0 = new float[8];
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
        int[] verts;
        try {
            verts = quad.getVertices();
        } catch (Throwable t) { return; }
        if (verts.length < 32) return;
        final int stride = 8;
        int[] vertexColors = new int[4];
        // First pass: extract local coordinates and UV/colors
        float[] localPos = new float[12];
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

        // Apply overlay offset in local coordinate system (0-1 range) for precision
        applyLocalOverlayOffset(localPos, overlayIndex);

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
        float[] normal = computeFaceNormal(positions);

        long posHash = computePositionHash(positions);
        Map<Long, List<OverlayQuadData>> cache = isCtmOverlay ? overlayCacheCTM : overlayCacheVanilla;
        List<OverlayQuadData> overlayList = cache.computeIfAbsent(posHash, k -> new ArrayList<>());
        // Use the provided overlayIndex from caller (for progressive offset assignment)
        OverlayQuadData data = new OverlayQuadData(positions.clone(), normal, uv0.clone(), overlayColorUv, spriteKey, overlayColor, isCtmOverlay, overlayIndex);
        overlayList.add(data);
    }
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
        if (!className.contains("continuity") && !className.contains("ctm") && !className.contains("connected")) {
            return false;
        }
        // Check sprite diversity (CTM uses multiple sprite variants)
        Set<String> uniqueSprites = new HashSet<>();
        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) continue;
            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
            if (spriteKey.contains("_overlay")) continue;
            // Fix: CTM uses underscore format (e.g., glass_pane_5), not slash format
            String baseSprite = spriteKey.replaceAll("_\\d+$", "");
            uniqueSprites.add(baseSprite);
        }
        return uniqueSprites.size() > 1;
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
            BlockPos neighbor = pos.relative(dir);
            if (isOutsideRegion(neighbor)) return false;
            if (!isNeighborSolid(neighbor)) return false;
        }
        return true;
    }
    private boolean isFaceOccluded(BlockPos pos, Direction face) {
        BlockPos neighbor = pos.relative(face);
        if (isOutsideRegion(neighbor)) return false;
        return isNeighborSolid(neighbor);
    }
    private boolean isFaceOccludedBySameBlock(BlockState state, BlockPos pos, Direction face) {
        BlockPos neighbor = pos.relative(face);
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
    private float[] computeFaceNormal(float[] positions) {
        float ax = positions[3] - positions[0];
        float ay = positions[4] - positions[1];
        float az = positions[5] - positions[2];
        float bx = positions[6] - positions[0];
        float by = positions[7] - positions[1];
        float bz = positions[8] - positions[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }
    private int computeTintColor(BlockState state, BlockPos pos, BakedQuad quad) {
        if (quad.getTintIndex() < 0) return 0xFFFFFFFF;
        int argb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
        return (argb == -1) ? 0xFFFFFFFF : argb;
    }
    private float[] whiteColor() {
        return new float[]{1f,1f,1f,1f, 1f,1f,1f,1f, 1f,1f,1f,1f, 1f,1f,1f,1f};
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
    public boolean hadMissingNeighborAndReset() {
        boolean result = missingNeighborDetected;
        missingNeighborDetected = false;
        return result;
    }
}
