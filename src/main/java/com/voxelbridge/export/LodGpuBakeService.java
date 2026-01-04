package com.voxelbridge.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.GpuBakeRenderPump;
import com.voxelbridge.voxy.client.core.model.ModelBakerySubsystem;
import com.voxelbridge.voxy.client.core.model.TextureUtils;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.voxy.mesh.VoxelMesher;
import com.voxelbridge.voxy.mesh.LodFaceMeta;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

public final class LodGpuBakeService implements VoxelMesher.LodOverlayProvider, AutoCloseable {
    private static final String SPRITE_PREFIX = "voxelbridge:lod/bake";
    private static final String TRANSPARENT_SPRITE = "voxelbridge:transparent";
    private static final int OVERLAY_OFFSET = 500000;
    // Reuse bake results across exports/instances to avoid duplicate GPU work
    private static final ConcurrentHashMap<Integer, FaceBakeData> SHARED_BAKED_DATA = new ConcurrentHashMap<>();
    private static final Set<Integer> SHARED_BAKED_IDS = ConcurrentHashMap.newKeySet();

    // Debug flag: set to true to save bake debug images to disk
    private static final boolean BAKE_DEBUG = true;
    private static final File DEBUG_DIR = new File("bake_debug");

    private final ExportContext ctx;
    private final Mapper mapper;
    private final ModelBakerySubsystem modelBakery;
    private final ConcurrentHashMap<Integer, FaceBakeData> bakedData;
    private final Set<Integer> bakedIds;

    public LodGpuBakeService(Mapper mapper, ExportContext ctx) {
        this.ctx = ctx;
        this.mapper = mapper;
        this.bakedData = SHARED_BAKED_DATA;
        this.bakedIds = SHARED_BAKED_IDS;
        this.modelBakery = runOnRenderThread(() -> new ModelBakerySubsystem(mapper, true));
        // We now include overlays in the GPU bake (default behavior of ModelTextureBakery)
        // effectively baking them into the base texture, aligning with Voxy's approach.
        this.modelBakery.factory.setBakeListener(this::handleBakeResult);
        GpuBakeRenderPump.register(this.modelBakery);
    }

    public void bakeBlockIds(IntOpenHashSet blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return;
        }
        IntOpenHashSet waitSet = new IntOpenHashSet();
        
        for (int blockId : blockIds) {
            BlockState state = this.mapper.getBlockStateFromBlockId(blockId);
            boolean hasOverlay = false;
            for (Direction dir : Direction.values()) {
                if (hasOverlaySprite(state, dir)) {
                    hasOverlay = true;
                    break;
                }
            }
            boolean needsBaseBake = needsBakeForContext(blockId, false);
            if (needsBaseBake) {
                modelBakery.requestBlockBake(blockId);
                waitSet.add(blockId);
            }

            if (hasOverlay) {
                int overlayReqId = blockId + OVERLAY_OFFSET;
                boolean needsOverlayBake = needsBakeForContext(blockId, true);
                if (needsOverlayBake) {
                    modelBakery.requestBlockBake(overlayReqId);
                    waitSet.add(overlayReqId);
                }
            }
        }
        waitForBakes(waitSet);
    }

    @Override
    public boolean hasTextures(int blockId) {
        return bakedData.containsKey(blockId);
    }

    @Override
    public String getSpriteKey(int blockId, Direction dir) {
        FaceBakeData data = bakedData.get(blockId);
        if (data == null) {
            return null;
        }
        return data.spriteKeys[dir.get3DDataValue()];
    }

    @Override
    public String getOverlaySpriteKey(int blockId, Direction dir) {
        FaceBakeData data = bakedData.get(blockId);
        if (data == null) {
            return null;
        }
        return data.overlayKeys[dir.get3DDataValue()];
    }

    @Override
    public LodFaceMeta getFaceMeta(int blockId, Direction dir) {
        FaceBakeData data = bakedData.get(blockId);
        if (data == null) {
            return null;
        }
        return data.metas[dir.get3DDataValue()];
    }

    @Override
    public void close() {
        GpuBakeRenderPump.unregister(this.modelBakery);
        runOnRenderThread(() -> {
            this.modelBakery.shutdown();
            return null;
        });
    }

    private void waitForBakes(IntOpenHashSet blockIds) {
        long startTime = System.currentTimeMillis();
        long lastLogTime = startTime;
        while (!allBaked(blockIds)) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 2000) {
                int total = blockIds.size();
                int done = 0;
                StringBuilder missing = new StringBuilder();
                int missingCount = 0;
                for (int id : blockIds) {
                    if (bakedIds.contains(id)) {
                        done++;
                    } else {
                        if (missingCount < 5) {
                            missing.append(id).append(",");
                        }
                        missingCount++;
                    }
                }
                VoxelBridgeLogger.warn(LogModule.BAKE, String.format(
                    "[Bake] Waiting... Finished: %d/%d. Missing (first 5): %s", done, total, missing.toString()));
                lastLogTime = now;
            }
        }
    }

    private boolean allBaked(IntOpenHashSet blockIds) {
        for (int blockId : blockIds) {
            if (!bakedIds.contains(blockId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines whether the current ExportContext has the baked sprite images for the given block id.
     * If not, we need to re-bake (even if the shared bakedIds already contains the id) to repopulate
     * this session's TextureRepository and avoid missing textures on subsequent exports.
     */
    private boolean needsBakeForContext(int blockId, boolean overlay) {
        int reqId = overlay ? blockId + OVERLAY_OFFSET : blockId;
        if (!bakedIds.contains(reqId)) {
            return true;
        }
        FaceBakeData data = bakedData.get(blockId);
        if (data == null) {
            return true;
        }
        String[] keys = overlay ? data.overlayKeys : data.spriteKeys;
        if (keys == null) {
            return true;
        }
        for (String key : keys) {
            if (key == null) continue;
            if (ctx.getCachedSpriteImage(key) == null) {
                return true;
            }
        }
        return false;
    }

    private void handleBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean darkenedTinting, boolean hasBakedTint) {
        int checkMode = selectCheckMode(blockState);
        boolean isFluid = blockState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
        boolean isOverlayResult = blockId >= OVERLAY_OFFSET;
        int realId = isOverlayResult ? blockId - OVERLAY_OFFSET : blockId;

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.BAKE)) {
            VoxelBridgeLogger.debug(LogModule.BAKE, String.format(
                "[Bake] reqId=%d realId=%d state=%s check=%s darkened=%s bakedTint=%s overlay=%s",
                blockId, realId, blockState, checkModeName(checkMode), darkenedTinting, hasBakedTint, isOverlayResult));
        }
        
        FaceBakeData data = bakedData.computeIfAbsent(realId, k -> new FaceBakeData());
        
        for (Direction dir : Direction.values()) {
            int face = dir.get3DDataValue();
            
            boolean faceHasBakedTint = hasBakedTint;
            if (!isFluid && hasBakedTint) {
                // If not fluid, refine based on whether this specific face has an overlay
                faceHasBakedTint = hasOverlaySprite(blockState, dir);
            }
            
            // For overlay pass, we only care if it actually has overlay data
            if (isOverlayResult) {
                // Overlay Pass
                FaceEntry entry = bakeFace(blockId, dir, textureData[face], checkMode, faceHasBakedTint);
                // Only set if we actually got a sprite (not transparent)
                if (!TRANSPARENT_SPRITE.equals(entry.spriteKey)) {
                    data.overlayKeys[face] = entry.spriteKey;
                }
            } else {
                // Base Pass
                FaceEntry entry = bakeFace(blockId, dir, textureData[face], checkMode, faceHasBakedTint);
                data.spriteKeys[face] = entry.spriteKey;
                data.metas[face] = entry.meta();
            }
        }
        // Mark the REQUEST ID as baked
        bakedIds.add(blockId);
    }

    private FaceEntry bakeFace(int blockId, Direction dir, ColourDepthTextureData tex, int checkMode, boolean hasBakedTint) {
        int w = tex.width();
        int h = tex.height();
        int[] basePixels = new int[w * h];
        int[] rawPixels = new int[w * h];  // For debug: unflipped version

        int[] colours = tex.colour();
        int[] depths = tex.depth();
        boolean hasBase = false;
        int written = 0;
        int baseCount = 0;
        for (int y = 0; y < h; y++) {
            int srcRow = y * w;
            int dstRow = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int srcIdx = srcRow + x;
                if (!isWritten(colours[srcIdx], depths[srcIdx], checkMode)) {
                    continue;
                }
                written++;
                int argb = toArgb(colours[srcIdx]);
                int dstIdx = dstRow + x;
                basePixels[dstIdx] = argb;
                rawPixels[srcIdx] = argb;  // Debug: keep original position
                hasBase = true;
                baseCount++;
            }
        }

        LodFaceMeta meta = buildFaceMeta(tex, checkMode, hasBakedTint, blockId, dir);

        // Debug: save bake result images (use /voxelbridge bakedebug command instead)
        // if (BAKE_DEBUG && hasBase) {
        //     saveBakeDebugImages(blockId, dir, w, h, rawPixels, basePixels, meta);
        // }

        String baseKey;
        if (hasBase) {
            baseKey = buildSpriteKey(blockId, dir, false);
            ctx.cacheSpriteImage(baseKey, createImage(w, h, basePixels));
        } else {
            baseKey = TRANSPARENT_SPRITE;
        }

        String overlayKey = null;

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.BAKE) && written > 0) {
            VoxelBridgeLogger.debug(LogModule.BAKE, String.format(
                "[Bake][Face] blockId=%d dir=%s size=%dx%d written=%d base=%d overlay=%d metaEmpty=%s bounds=[%d,%d,%d,%d] depth=%.4f",
                blockId,
                dir.getSerializedName(),
                w,
                h,
                written,
                baseCount,
                0,
                meta.empty,
                meta.minX,
                meta.maxX,
                meta.minY,
                meta.maxY,
                meta.depth));
        }
        if (written > 0 && meta.empty) {
            VoxelBridgeLogger.warn(LogModule.BAKE, String.format(
                "[Bake][Face] blockId=%d dir=%s written=%d but meta marked empty",
                blockId, dir.getSerializedName(), written));
        }

        return new FaceEntry(baseKey, meta);
    }

    private static LodFaceMeta buildFaceMeta(ColourDepthTextureData tex, int checkMode, boolean hasBakedTint, int blockId, Direction dir) {
        int written = TextureUtils.getWrittenPixelCount(tex, checkMode);
        if (written == 0) {
            return new LodFaceMeta(0, 0, 0, 0, 0f, true, hasBakedTint);
        }
        int[] bounds = TextureUtils.computeBounds(tex, checkMode);
        float depth = TextureUtils.computeDepth(tex, TextureUtils.DEPTH_MODE_AVG, checkMode);
        if (depth < -0.1f || bounds[0] >= tex.width() || bounds[1] < 0 || bounds[2] >= tex.height() || bounds[3] < 0) {
            return new LodFaceMeta(0, 0, 0, 0, 0f, true, hasBakedTint);
        }

        // bounds[0..3] = [minX, maxX, minY, maxY] in OpenGL texture coords (Y=0 at bottom)
        // These bounds directly correspond to world-space geometry cropping:
        // - For UP/DOWN faces: minX/maxX -> world X, minY/maxY -> world Z
        // - For NORTH/SOUTH faces: minX/maxX -> world X, minY/maxY -> world Y
        // - For EAST/WEST faces: minX/maxX -> world Z, minY/maxY -> world Y
        //
        // The image file is Y-flipped separately in bakeFace(), but the geometry bounds
        // should remain in OpenGL coords since that matches world-space orientation.
        int minX = clamp(bounds[0], 0, tex.width() - 1);
        int maxX = clamp(bounds[1], 0, tex.width() - 1);
        int minY = clamp(bounds[2], 0, tex.height() - 1);
        int maxY = clamp(bounds[3], 0, tex.height() - 1);

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.BAKE)) {
            VoxelBridgeLogger.debug(LogModule.BAKE, String.format(
                "[FaceMeta] blockId=%d dir=%s bounds=[%d,%d,%d,%d] depth=%.4f written=%d",
                blockId, dir.getSerializedName(),
                minX, maxX, minY, maxY, depth, written));
        }

        return new LodFaceMeta(minX, maxX, minY, maxY, depth, false, hasBakedTint);
    }

    private static int selectCheckMode(BlockState state) {
        if (state == null) {
            return TextureUtils.WRITE_CHECK_ALPHA;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return TextureUtils.WRITE_CHECK_ALPHA;
        }
        net.minecraft.client.renderer.RenderType layer = net.minecraft.client.renderer.ItemBlockRenderTypes.getChunkRenderType(state);
        if (layer == net.minecraft.client.renderer.RenderType.solid()) {
            return TextureUtils.WRITE_CHECK_STENCIL;
        }
        return TextureUtils.WRITE_CHECK_ALPHA;
    }

    private static String checkModeName(int checkMode) {
        if (checkMode == TextureUtils.WRITE_CHECK_STENCIL) {
            return "STENCIL";
        }
        if (checkMode == TextureUtils.WRITE_CHECK_DEPTH) {
            return "DEPTH";
        }
        return "ALPHA";
    }

    private static boolean isWritten(int colour, int depth, int checkMode) {
        if (checkMode == TextureUtils.WRITE_CHECK_STENCIL) {
            return (depth & 0xFF) != 0;
        }
        if (checkMode == TextureUtils.WRITE_CHECK_DEPTH) {
            return (depth >>> 8) != ((1 << 24) - 1);
        }
        return ((colour >>> 24) & 0xFF) > 1;
    }

    private static int toArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage createImage(int w, int h, int[] pixels) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    private static String buildSpriteKey(int blockId, Direction dir, boolean overlay) {
        String face = dir.getSerializedName();
        if (overlay) {
            return SPRITE_PREFIX + "/block_" + blockId + "/" + face + "_overlay";
        }
        return SPRITE_PREFIX + "/block_" + blockId + "/" + face;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static <T> T runOnRenderThread(Supplier<T> task) {
        if (RenderSystem.isOnRenderThread()) {
            return task.get();
        }
        return Minecraft.getInstance().submit(task).join();
    }

    private static boolean hasOverlaySprite(BlockState state, Direction dir) {
        var model = Minecraft.getInstance()
            .getModelManager()
            .getBlockModelShaper()
            .getBlockModel(state);
        
        long seed = 42L;
        var rand = net.minecraft.util.RandomSource.create(seed);
        
        // Check main layer
        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
        if (checkQuadsForOverlay(model.getQuads(state, dir, rand, ModelData.EMPTY, layer))) {
            return true;
        }
        
        // Check "null" layer (usually where overlays are)
        rand.setSeed(seed);
        if (checkQuadsForOverlay(model.getQuads(state, dir, rand, ModelData.EMPTY, null))) {
            return true;
        }
        
        return false;
    }

    private static boolean checkQuadsForOverlay(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (isOverlaySprite(quad.getSprite())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOverlaySprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return false;
        }
        return sprite.contents().name().toString().contains("overlay");
    }

    private static final class FaceBakeData {
        private final String[] spriteKeys = new String[6];
        private final String[] overlayKeys = new String[6];
        private final LodFaceMeta[] metas = new LodFaceMeta[6];
    }

    private record FaceEntry(String spriteKey, LodFaceMeta meta) {}
}
