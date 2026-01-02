package com.voxelbridge.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.bakery.ModelTextureBakery;
import com.voxelbridge.voxy.client.core.rendering.util.RawDownloadStream;
import com.voxelbridge.voxy.common.util.MemoryBuffer;
import net.minecraft.world.level.block.state.BlockState;

import static org.lwjgl.opengl.GL11.glFinish;

public final class GpuBakeDebugService implements AutoCloseable {
    private static final int FACE_COUNT = 6;

    private final int size;
    private final ModelTextureBakery bakery;
    private final RawDownloadStream downstream;

    public GpuBakeDebugService(int size) {
        this.size = size;
        this.bakery = new ModelTextureBakery(size, size);
        this.bakery.setCaptureUvPoints(true);
        this.downstream = new RawDownloadStream(bufferBytes(size));
    }

    public int getSize() {
        return size;
    }

    public BakeResult bake(BlockState state) {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("GpuBakeDebugService.bake must run on the render thread");
        }
        RawBakeResult result = new RawBakeResult(bufferBytes(size));
        int allocation = this.downstream.download(bufferBytes(size), result::cpyFrom);
        int flags = this.bakery.renderToStream(state, this.downstream.getBufferId(), allocation);
        ModelTextureBakery.UvStats uvStats = this.bakery.getLastUvStats();
        this.downstream.submit();
        glFinish();
        this.downstream.tick();
        if (!result.ready) {
            this.downstream.tick();
        }
        ColourDepthTextureData[] textures = parse(result.rawData, size);
        result.rawData.free();
        return new BakeResult(textures, flags, uvStats);
    }

    @Override
    public void close() {
        this.bakery.free();
        this.downstream.free();
    }

    public record BakeResult(ColourDepthTextureData[] textures, int flags, ModelTextureBakery.UvStats uvStats) {}

    private static int bufferBytes(int size) {
        return size * size * 2 * 4 * FACE_COUNT;
    }

    private static ColourDepthTextureData[] parse(MemoryBuffer rawData, int size) {
        ColourDepthTextureData[] textureData = new ColourDepthTextureData[FACE_COUNT];
        long ptr = rawData.address;
        int faceSize = size * size;
        for (int face = 0; face < FACE_COUNT; face++) {
            long faceDataPtr = ptr + (faceSize * 4L) * face * 2L;
            int[] colour = new int[faceSize];
            int[] depth = new int[faceSize];
            for (int i = 0; i < faceSize; i++) {
                colour[i] = org.lwjgl.system.MemoryUtil.memGetInt(faceDataPtr + (i * 4L * 2L));
                depth[i] = org.lwjgl.system.MemoryUtil.memGetInt(faceDataPtr + (i * 4L * 2L) + 4L);
            }
            textureData[face] = new ColourDepthTextureData(colour, depth, size, size);
        }
        return textureData;
    }

    private static final class RawBakeResult {
        private final MemoryBuffer rawData;
        private volatile boolean ready;

        private RawBakeResult(int bytes) {
            this.rawData = new MemoryBuffer(bytes);
        }

        private void cpyFrom(long ptr) {
            this.rawData.cpyFrom(ptr);
            this.ready = true;
        }
    }
}
