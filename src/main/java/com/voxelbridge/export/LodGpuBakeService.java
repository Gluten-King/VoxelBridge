package com.voxelbridge.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.GpuBakeRenderPump;
import com.voxelbridge.voxy.client.core.model.ModelBakerySubsystem;
import com.voxelbridge.voxy.client.core.model.TextureUtils;
import com.voxelbridge.voxy.client.core.model.bakery.CpuModelTextureBakery;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.voxy.mesh.VoxelMesher;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class LodGpuBakeService implements VoxelMesher.LodOverlayProvider, AutoCloseable {
    private static final String SPRITE_PREFIX = "voxelbridge:lod/bake";
    private static final String TRANSPARENT_SPRITE = "voxelbridge:transparent";

    private final ExportContext ctx;
    private final ModelBakerySubsystem modelBakery;
    private final ConcurrentHashMap<Integer, FaceBakeData> bakedData = new ConcurrentHashMap<>();
    private final Set<Integer> bakedIds = ConcurrentHashMap.newKeySet();
    private final CpuModelTextureBakery overlayBakery = new CpuModelTextureBakery();

    public LodGpuBakeService(Mapper mapper, ExportContext ctx) {
        this.ctx = ctx;
        this.modelBakery = runOnRenderThread(() -> new ModelBakerySubsystem(mapper, true));
        if (this.modelBakery.factory.bakery != null) {
            this.modelBakery.factory.bakery.setIncludeOverlay(false);
        }
        this.modelBakery.factory.setBakeListener(this::handleBakeResult);
        GpuBakeRenderPump.register(this.modelBakery);
    }

    public void bakeBlockIds(IntOpenHashSet blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return;
        }
        for (int blockId : blockIds) {
            modelBakery.requestBlockBake(blockId);
        }
        waitForBakes(blockIds);
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
    public VoxelMesher.LodFaceMeta getFaceMeta(int blockId, Direction dir) {
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
        while (!allBaked(blockIds)) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
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

    private void handleBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean darkenedTinting) {
        int checkMode = selectCheckMode(blockState);
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.BAKE)) {
            VoxelBridgeLogger.debug(LogModule.BAKE, String.format(
                "[Bake] blockId=%d state=%s check=%s darkened=%s",
                blockId, blockState, checkModeName(checkMode), darkenedTinting));
        }
        String[] overlaySprites = resolveOverlaySprites(blockId, blockState);
        FaceBakeData data = new FaceBakeData();
        for (Direction dir : Direction.values()) {
            int face = dir.get3DDataValue();
            FaceEntry entry = bakeFace(blockId, dir, textureData[face], checkMode);
            data.spriteKeys[face] = entry.spriteKey();
            data.overlayKeys[face] = overlaySprites[face];
            data.metas[face] = entry.meta();
        }
        bakedData.put(blockId, data);
        bakedIds.add(blockId);
    }

    private FaceEntry bakeFace(int blockId, Direction dir, ColourDepthTextureData tex, int checkMode) {
        int w = tex.width();
        int h = tex.height();
        int[] basePixels = new int[w * h];

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
                hasBase = true;
                baseCount++;
            }
        }

        VoxelMesher.LodFaceMeta meta = buildFaceMeta(tex, checkMode);

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

    private static VoxelMesher.LodFaceMeta buildFaceMeta(ColourDepthTextureData tex, int checkMode) {
        int written = TextureUtils.getWrittenPixelCount(tex, checkMode);
        if (written == 0) {
            return new VoxelMesher.LodFaceMeta(0, 0, 0, 0, 0f, true);
        }
        int[] bounds = TextureUtils.computeBounds(tex, checkMode);
        float depth = TextureUtils.computeDepth(tex, TextureUtils.DEPTH_MODE_AVG, checkMode);
        if (depth < -0.1f || bounds[0] >= tex.width() || bounds[1] < 0 || bounds[2] >= tex.height() || bounds[3] < 0) {
            return new VoxelMesher.LodFaceMeta(0, 0, 0, 0, 0f, true);
        }
        int minX = clamp(bounds[0], 0, tex.width() - 1);
        int maxX = clamp(bounds[1], 0, tex.width() - 1);
        int minY = clamp(bounds[2], 0, tex.height() - 1);
        int maxY = clamp(bounds[3], 0, tex.height() - 1);
        return new VoxelMesher.LodFaceMeta(minX, maxX, minY, maxY, depth, false);
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

    private String[] resolveOverlaySprites(int blockId, BlockState state) {
        if (state == null || ctx == null) {
            return new String[6];
        }
        return runOnRenderThread(() -> {
            if (!hasOverlayQuads(state)) {
                return new String[6];
            }
            var bake = overlayBakery.bake(state, CpuModelTextureBakery.BakeMode.OVERLAY_ONLY);
            ColourDepthTextureData[] textures = bake.textures();
            String[] overlayKeys = new String[6];
            int checkMode = TextureUtils.WRITE_CHECK_ALPHA;
            for (Direction dir : Direction.values()) {
                ColourDepthTextureData tex = textures[dir.get3DDataValue()];
                overlayKeys[dir.get3DDataValue()] = buildOverlaySprite(blockId, dir, tex, checkMode);
            }
            return overlayKeys;
        });
    }

    private boolean hasOverlayQuads(BlockState state) {
        var model = Minecraft.getInstance()
            .getModelManager()
            .getBlockModelShaper()
            .getBlockModel(state);
        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
        long seed = 42L;
        var rand = net.minecraft.util.RandomSource.create(seed);

        for (Direction dir : Direction.values()) {
            rand.setSeed(seed);
            if (findOverlaySprite(model.getQuads(state, dir, rand, ModelData.EMPTY, layer)) != null) {
                return true;
            }
            rand.setSeed(seed);
            if (findOverlaySprite(model.getQuads(state, dir, rand, ModelData.EMPTY, null)) != null) {
                return true;
            }
        }

        rand.setSeed(seed);
        return findOverlaySprite(model.getQuads(state, null, rand, ModelData.EMPTY, null)) != null;
    }

    private String buildOverlaySprite(int blockId, Direction dir, ColourDepthTextureData tex, int checkMode) {
        if (tex == null) {
            return null;
        }
        String key = buildSpriteKey(blockId, dir, true);
        if (ctx.getCachedSpriteImage(key) != null) {
            return key;
        }
        int w = tex.width();
        int h = tex.height();
        int[] overlayPixels = new int[w * h];
        int[] colours = tex.colour();
        int[] depths = tex.depth();
        boolean hasOverlay = false;
        for (int y = 0; y < h; y++) {
            int srcRow = y * w;
            int dstRow = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int srcIdx = srcRow + x;
                if (!isWritten(colours[srcIdx], depths[srcIdx], checkMode)) {
                    continue;
                }
                int argb = toArgb(colours[srcIdx]);
                overlayPixels[dstRow + x] = argb;
                hasOverlay = true;
            }
        }
        if (!hasOverlay) {
            return null;
        }
        ctx.cacheSpriteImage(key, createImage(w, h, overlayPixels));
        return key;
    }

    private static TextureAtlasSprite findOverlaySprite(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) {
            return null;
        }
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite != null && isOverlaySprite(sprite)) {
                return sprite;
            }
        }
        return null;
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
        private final VoxelMesher.LodFaceMeta[] metas = new VoxelMesher.LodFaceMeta[6];
    }

    private record FaceEntry(String spriteKey, VoxelMesher.LodFaceMeta meta) {}
}
