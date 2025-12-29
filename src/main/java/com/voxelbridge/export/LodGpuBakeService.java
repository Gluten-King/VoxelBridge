package com.voxelbridge.export;

import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.ModelFactory;
import com.voxelbridge.voxy.client.core.model.ModelStore;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.voxy.mesh.VoxelMesher;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

public final class LodGpuBakeService implements AutoCloseable, VoxelMesher.LodOverlayProvider {
    private final ExportContext ctx;
    private final ModelStore modelStore;
    private final ModelFactory modelFactory;
    private final Int2ObjectOpenHashMap<String[]> bakedSpriteKeys = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String[]> bakedOverlayKeys = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<VoxelMesher.LodFaceMeta[]> bakedFaceMeta = new Int2ObjectOpenHashMap<>();

    private final Path debugOutDir;

    public LodGpuBakeService(ExportContext ctx, Mapper mapper, Path outDir) {
        this.ctx = ctx;
        this.debugOutDir = outDir;
        // Initialize model subsystems on the main thread to ensure thread safety (OpenGL context, etc.)
        Object[] systems = Minecraft.getInstance().submit(() -> {
            ModelStore ms = new ModelStore();
            ModelFactory mf = new ModelFactory(mapper, ms);
            mf.setBakeListener(this::handleBakeResult);
            return new Object[]{ms, mf};
        }).join();
        
        this.modelStore = (ModelStore) systems[0];
        this.modelFactory = (ModelFactory) systems[1];
    }

    public void bakeBlockIds(Set<Integer> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return;
        }

        // 1. Submit requests to add entries (Main Thread)
        Minecraft.getInstance().submit(() -> {
            for (int blockId : blockIds) {
                if (blockId == 0) {
                    continue;
                }
                modelFactory.addEntry(blockId);
            }
        }).join();

        // 2. Process baking in a loop, yielding to the main thread between ticks
        // This prevents freezing the game or triggering the watchdog by blocking the main thread for too long.
        while (true) {
            boolean done = Minecraft.getInstance().submit(() -> {
                modelFactory.tickAndProcessUploads();
                modelFactory.processAllThings();
                return allBaked(blockIds);
            }).join();

            if (done) {
                break;
            }

            try {
                // Short sleep to allow other tasks on the main thread to run / prevent busy loop
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during GPU bake", e);
            }
        }
    }

    private boolean allBaked(Set<Integer> blockIds) {
        for (int blockId : blockIds) {
            if (blockId == 0) {
                continue;
            }
            if (!modelFactory.hasModelForBlockId(blockId)) {
                return false;
            }
        }
        return true;
    }

    private void handleBakeResult(int blockId, BlockState blockState, ColourDepthTextureData[] textureData, boolean darkenedTinting) {
        String[] faceKeys = bakedSpriteKeys.get(blockId);
        if (faceKeys == null) {
            faceKeys = new String[6];
            bakedSpriteKeys.put(blockId, faceKeys);
        }
        String[] overlayKeys = bakedOverlayKeys.get(blockId);
        if (overlayKeys == null) {
            overlayKeys = new String[6];
            bakedOverlayKeys.put(blockId, overlayKeys);
        }
        VoxelMesher.LodFaceMeta[] faceMeta = bakedFaceMeta.get(blockId);
        if (faceMeta == null) {
            faceMeta = new VoxelMesher.LodFaceMeta[6];
            bakedFaceMeta.put(blockId, faceMeta);
        }
        int checkMode = resolveCheckMode(blockState);

        for (Direction dir : Direction.values()) {
            int faceIndex = dir.get3DDataValue();
            String spriteKey = makeSpriteKey(blockId, dir);
            String overlayKey = makeOverlayKey(blockId, dir);
            BufferedImage[] split = splitTinted(textureData[faceIndex], checkMode);
            ctx.cacheSpriteImage(spriteKey, split[0]);
            faceKeys[faceIndex] = spriteKey;
            if (split[1] != null) {
                ctx.cacheSpriteImage(overlayKey, split[1]);
                overlayKeys[faceIndex] = overlayKey;
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.LOD_BAKE)) {
                    VoxelBridgeLogger.debug(LogModule.LOD_BAKE, String.format(
                        "[LodGpuBakeService] blockId=%d dir=%s HAS OVERLAY: base=%s overlay=%s",
                        blockId, dir.getName(), spriteKey, overlayKey));
                }
            } else {
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.LOD_BAKE)) {
                    VoxelBridgeLogger.debug(LogModule.LOD_BAKE, String.format(
                        "[LodGpuBakeService] blockId=%d dir=%s NO OVERLAY: base=%s",
                        blockId, dir.getName(), spriteKey));
                }
            }
            faceMeta[faceIndex] = computeFaceMeta(textureData[faceIndex], checkMode);
        }

        if (shouldDumpDebug(blockId)) {
            VoxelBridgeLogger.info(LogModule.LOD_BAKE, String.format(
                "[Bake] blockId=%d state=%s checkMode=%s",
                blockId,
                blockState,
                checkMode == WriteCheckMode.STENCIL ? "stencil" : "alpha"));
            for (Direction dir : Direction.values()) {
                int faceIndex = dir.get3DDataValue();
                VoxelMesher.LodFaceMeta meta = faceMeta[faceIndex];
                if (meta != null) {
                    VoxelBridgeLogger.info(LogModule.LOD_BAKE, String.format(
                        "[Bake] face=%s sprite=%s minX=%d maxX=%d minY=%d maxY=%d depthOffset=%.6f empty=%s",
                        dir.getName(),
                        faceKeys[faceIndex],
                        meta.minX,
                        meta.maxX,
                        meta.minY,
                        meta.maxY,
                        meta.depth,
                        meta.empty));
                }
                dumpDebug(blockId, dir, textureData[faceIndex], checkMode, faceMeta[faceIndex]);
            }
        }
    }

    private static String makeSpriteKey(int blockId, Direction dir) {
        return "voxelbridge:lod/baked/" + blockId + "/" + dir.getName();
    }

    private static String makeOverlayKey(int blockId, Direction dir) {
        return "voxelbridge:lod/baked/" + blockId + "/" + dir.getName() + "/overlay";
    }

    private static BufferedImage toImage(ColourDepthTextureData data, int checkMode) {
        int width = data.width();
        int height = data.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] colours = data.colour();
        int[] depth = data.depth();
        int maxDepth = (1 << 24) - 1;
        boolean wroteAny = false;

        for (int y = 0; y < height; y++) {
            int flippedY = (height - 1 - y) * width;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (!wasPixelWritten(colours[idx], depth[idx], checkMode, maxDepth)) {
                    out[flippedY + x] = 0;
                    continue;
                }
                out[flippedY + x] = abgrToArgb(colours[idx]);
                wroteAny = true;
            }
        }

        if (!wroteAny && checkMode == WriteCheckMode.STENCIL) {
            for (int y = 0; y < height; y++) {
                int flippedY = (height - 1 - y) * width;
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = row + x;
                    int depth24 = depth[idx] >>> 8;
                    if (depth24 >= maxDepth) {
                        out[flippedY + x] = 0;
                        continue;
                    }
                    out[flippedY + x] = abgrToArgb(colours[idx]);
                }
            }
        }

        return image;
    }

    /**
     * 将 tint 区域拆成独立贴图：基底清除 tint 像素，overlay 只保留 tint 像素，
     * 这样在导出时可以用 overlay sprite 乘以顶点色，而基底保持原色。
     */
    private static BufferedImage[] splitTinted(ColourDepthTextureData data, int checkMode) {
        int len = data.colour().length;
        int[] baseColour = data.colour().clone();
        int[] baseDepth = data.depth().clone();
        int[] overlayColour = new int[len];
        int[] overlayDepth = new int[len];
        int maxDepth = (1 << 24) - 1;
        int tintedWritten = 0;
        int nonTintedWritten = 0;
        for (int i = 0; i < len; i++) {
            boolean tinted = (data.depth()[i] & 0x80) != 0;
            boolean written = wasPixelWritten(baseColour[i], baseDepth[i], checkMode, maxDepth);
            if (!written) {
                continue;
            }
            if (tinted) {
                tintedWritten++;
            } else {
                nonTintedWritten++;
            }
        }

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.LOD_BAKE)) {
            VoxelBridgeLogger.debug(LogModule.LOD_BAKE, String.format(
                "[LodGpuBakeService.splitTinted] written tinted=%d nonTinted=%d total=%d",
                tintedWritten, nonTintedWritten, len));
        }

        // 全部是 tinted：保留原图，不拆 overlay
        if (tintedWritten > 0 && nonTintedWritten == 0) {
            BufferedImage baseImg = toImage(data, checkMode);
            return new BufferedImage[]{baseImg, null};
        }

        // 没有 tinted：只用原图
        if (tintedWritten == 0) {
            BufferedImage baseImg = toImage(data, checkMode);
            return new BufferedImage[]{baseImg, null};
        }

        // 部分 tinted：拆分 overlay
        for (int i = 0; i < len; i++) {
            boolean tinted = (data.depth()[i] & 0x80) != 0;
            if (tinted) {
                overlayColour[i] = baseColour[i];
                overlayDepth[i] = baseDepth[i];
                baseColour[i] = 0;
                baseDepth[i] = maxDepth << 8; // clear from base
            } else {
                overlayDepth[i] = maxDepth << 8;
            }
        }

        BufferedImage baseImg = toImage(new ColourDepthTextureData(baseColour, baseDepth, data.width(), data.height()), checkMode);
        BufferedImage overlayImg = toImage(new ColourDepthTextureData(overlayColour, overlayDepth, data.width(), data.height()), checkMode);
        return new BufferedImage[]{baseImg, overlayImg};
    }

    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int resolveCheckMode(BlockState state) {
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderType.solid();
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }
        return layer == RenderType.solid() ? WriteCheckMode.STENCIL : WriteCheckMode.ALPHA;
    }

    private static VoxelMesher.LodFaceMeta computeFaceMeta(ColourDepthTextureData data, int checkMode) {
        int width = data.width();
        int height = data.height();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        long sumDepth = 0;
        int count = 0;
        boolean wroteStencil = false;

        int[] colours = data.colour();
        int[] depth = data.depth();
        int maxDepth = (1 << 24) - 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                int depthRaw = depth[idx];
                if (checkMode == WriteCheckMode.STENCIL && (depthRaw & 0xFF) != 0) {
                    wroteStencil = true;
                }
                if (!wasPixelWritten(colours[idx], depthRaw, checkMode, maxDepth)) {
                    continue;
                }
                int yFlipped = height - 1 - y;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (yFlipped < minY) minY = yFlipped;
                if (yFlipped > maxY) maxY = yFlipped;
                sumDepth += (depthRaw >>> 8);
                count++;
            }
        }

        if (count == 0 && checkMode == WriteCheckMode.STENCIL && !wroteStencil) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = row + x;
                    int depth24 = depth[idx] >>> 8;
                    if (depth24 >= maxDepth) {
                        continue;
                    }
                    int yFlipped = height - 1 - y;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (yFlipped < minY) minY = yFlipped;
                    if (yFlipped > maxY) maxY = yFlipped;
                    sumDepth += depth24;
                    count++;
                }
            }
        }

        if (count == 0) {
            return new VoxelMesher.LodFaceMeta(0, 0, 0, 0, 0f, true);
        }

        int avgDepth = (int) (sumDepth / count);
        float depthOffset = u2fdepth(avgDepth);
        return new VoxelMesher.LodFaceMeta(minX, maxX, minY, maxY, depthOffset, false);
    }

    private static boolean wasPixelWritten(int colour, int depth, int mode, int maxDepth) {
        if (mode == WriteCheckMode.STENCIL) {
            return (depth & 0xFF) != 0;
        }
        if (mode == WriteCheckMode.ALPHA) {
            return ((colour >>> 24) & 0xFF) > 1;
        }
        return (depth >>> 8) != maxDepth;
    }

    private static float u2fdepth(int depth) {
        float depthF = (float) ((double) depth / ((1 << 24) - 1));
        depthF *= 2.0f;
        if (depthF > 1.00001f) {
            depthF = 1.0f;
        }
        return depthF;
    }

    private boolean shouldDumpDebug(int blockId) {
        if (!com.voxelbridge.config.ExportRuntimeConfig.isLodBakeDebugEnabled()) {
            return false;
        }
        int target = com.voxelbridge.config.ExportRuntimeConfig.getLodBakeDebugBlockId();
        return target < 0 || target == blockId;
    }

    private void dumpDebug(int blockId, Direction dir, ColourDepthTextureData data, int checkMode, VoxelMesher.LodFaceMeta meta) {
        try {
            Path dirPath = debugOutDir.resolve("debug").resolve("lod_bake").resolve("block_" + blockId);
            Files.createDirectories(dirPath);

            String faceName = dir.getName();
            BufferedImage raw = toRawImage(data);
            BufferedImage masked = toImage(data, checkMode);
            BufferedImage depthImg = toDepthImage(data);

            ImageIO.write(raw, "PNG", dirPath.resolve(faceName + "_raw.png").toFile());
            ImageIO.write(masked, "PNG", dirPath.resolve(faceName + "_masked.png").toFile());
            ImageIO.write(depthImg, "PNG", dirPath.resolve(faceName + "_depth.png").toFile());

            Path metaPath = dirPath.resolve(faceName + "_meta.txt");
            try (BufferedWriter writer = Files.newBufferedWriter(metaPath)) {
                writer.write("face=" + faceName);
                writer.newLine();
                writer.write("checkMode=" + (checkMode == WriteCheckMode.STENCIL ? "stencil" : "alpha"));
                writer.newLine();
                if (meta != null) {
                    writer.write("minX=" + meta.minX + " maxX=" + meta.maxX + " minY=" + meta.minY + " maxY=" + meta.maxY);
                    writer.newLine();
                    writer.write("depthOffset=" + meta.depth);
                    writer.newLine();
                    writer.write("empty=" + meta.empty);
                    writer.newLine();
                }
            }
            VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[Bake] wrote " + dirPath.toAbsolutePath());
        } catch (IOException ignored) {
        }
    }

    private static BufferedImage toRawImage(ColourDepthTextureData data) {
        int width = data.width();
        int height = data.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] colours = data.colour();
        for (int y = 0; y < height; y++) {
            int flippedY = (height - 1 - y) * width;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                out[flippedY + x] = abgrToArgb(colours[idx]);
            }
        }
        return image;
    }

    private static BufferedImage toDepthImage(ColourDepthTextureData data) {
        int width = data.width();
        int height = data.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] depth = data.depth();
        int maxDepth = (1 << 24) - 1;
        for (int y = 0; y < height; y++) {
            int flippedY = (height - 1 - y) * width;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                int depth24 = depth[idx] >>> 8;
                int stencil = depth[idx] & 0xFF;
                int d = (int) Math.round((depth24 / (double) maxDepth) * 255.0);
                int a = stencil;
                int argb = (a << 24) | (d << 16) | (d << 8) | d;
                out[flippedY + x] = argb;
            }
        }
        return image;
    }

    @Override
    public boolean hasTextures(int blockId) {
        return bakedSpriteKeys.containsKey(blockId);
    }

    @Override
    public String getSpriteKey(int blockId, Direction dir) {
        String[] faces = bakedSpriteKeys.get(blockId);
        if (faces == null) {
            return null;
        }
        return faces[dir.get3DDataValue()];
    }

    @Override
    public String getOverlaySpriteKey(int blockId, Direction dir) {
        String[] faces = bakedOverlayKeys.get(blockId);
        if (faces == null) {
            return null;
        }
        return faces[dir.get3DDataValue()];
    }

    @Override
    public VoxelMesher.LodFaceMeta getFaceMeta(int blockId, Direction dir) {
        VoxelMesher.LodFaceMeta[] faces = bakedFaceMeta.get(blockId);
        if (faces == null) {
            return null;
        }
        return faces[dir.get3DDataValue()];
    }

    @Override
    public void close() {
        Minecraft.getInstance().submit(() -> {
            modelFactory.free();
            modelStore.free();
            bakedSpriteKeys.clear();
            bakedOverlayKeys.clear();
            bakedFaceMeta.clear();
        }).join();
    }

    private static final class WriteCheckMode {
        private static final int STENCIL = 1;
        private static final int ALPHA = 3;
    }
}