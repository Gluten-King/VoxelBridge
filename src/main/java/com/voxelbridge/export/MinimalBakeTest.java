package com.voxelbridge.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.voxy.client.core.gl.GlFramebuffer;
import com.voxelbridge.voxy.client.core.gl.GlTexture;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.bakery.BudgetBufferRenderer;
import com.voxelbridge.voxy.client.core.model.bakery.ReuseVertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45C.glGetTextureImage;

/**
 * 最小化测试 - 测试 framebuffer 和渲染管线
 */
public final class MinimalBakeTest implements AutoCloseable {
    private final int width;
    private final int height;
    private final GlTexture colourTex;
    private final GlTexture depthTex;
    private final GlFramebuffer framebuffer;

    public MinimalBakeTest(int size) {
        this.width = size;
        this.height = size;
        this.colourTex = new GlTexture().store(GL_RGBA8, 1, size, size).name("MinimalTestColour");
        this.depthTex = new GlTexture().store(GL_DEPTH24_STENCIL8, 1, size, size).name("MinimalTestDepth");
        this.framebuffer = new GlFramebuffer()
                .bind(GL_COLOR_ATTACHMENT0, this.colourTex)
                .bind(GL_DEPTH_STENCIL_ATTACHMENT, this.depthTex)
                .setDrawBuffers(GL_COLOR_ATTACHMENT0)
                .verify()
                .name("MinimalTestFramebuffer");
    }

    public int getSize() {
        return width;
    }

    /**
     * 测试1: 只用 glClear 填充红色，验证 framebuffer 读取
     */
    public TestResult testClearColor() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Must run on render thread");
        }

        int oldFB = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] oldViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, oldViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
        GL11C.glViewport(0, 0, this.width, this.height);

        glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        glFinish();

        int[] colourData = new int[this.width * this.height];
        int[] depthData = new int[this.width * this.height];
        glGetTextureImage(this.colourTex.id, 0, GL_RGBA, GL_UNSIGNED_BYTE, colourData);
        glGetTextureImage(this.depthTex.id, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, depthData);

        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        GL11C.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        int nonZeroCount = 0;
        int redCount = 0;
        for (int i = 0; i < colourData.length; i++) {
            if (colourData[i] != 0) {
                nonZeroCount++;
            }
            int r = colourData[i] & 0xFF;
            int g = (colourData[i] >> 8) & 0xFF;
            int b = (colourData[i] >> 16) & 0xFF;
            int a = (colourData[i] >> 24) & 0xFF;
            if (r == 255 && g == 0 && b == 0 && a == 255) {
                redCount++;
            }
        }

        return new TestResult(
            "ClearColor",
            colourData.length,
            nonZeroCount,
            redCount,
            new ColourDepthTextureData(colourData, depthData, width, height)
        );
    }

    /**
     * 测试2: 使用 glReadPixels 而不是 glGetTextureImage
     */
    public TestResult testReadPixels() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Must run on render thread");
        }

        int oldFB = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] oldViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, oldViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
        GL11C.glViewport(0, 0, this.width, this.height);

        glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        glFinish();

        int[] colourData = new int[this.width * this.height];
        glReadPixels(0, 0, this.width, this.height, GL_RGBA, GL_UNSIGNED_BYTE, colourData);

        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        GL11C.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        int nonZeroCount = 0;
        int greenCount = 0;
        for (int i = 0; i < colourData.length; i++) {
            if (colourData[i] != 0) {
                nonZeroCount++;
            }
            int r = colourData[i] & 0xFF;
            int g = (colourData[i] >> 8) & 0xFF;
            int b = (colourData[i] >> 16) & 0xFF;
            int a = (colourData[i] >> 24) & 0xFF;
            if (r == 0 && g == 255 && b == 0 && a == 255) {
                greenCount++;
            }
        }

        int[] depthData = new int[this.width * this.height];
        return new TestResult(
            "ReadPixels",
            colourData.length,
            nonZeroCount,
            greenCount,
            new ColourDepthTextureData(colourData, depthData, width, height)
        );
    }

    /**
     * 测试3: 使用 BudgetBufferRenderer 渲染一个 quad
     */
    public TestResult testRenderQuad() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Must run on render thread");
        }

        int oldFB = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] oldViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, oldViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
        GL11C.glViewport(0, 0, this.width, this.height);

        // 清除为黑色
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        // 设置渲染状态
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        // 获取纹理ID
        int texId = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getId();

        // 创建顶点数据 - 一个覆盖整个视口的 quad
        // 使用 ReuseVertexConsumer 的格式: x, y, z, metadata(int), u, v
        long vertexData = MemoryUtil.nmemAlloc(4 * BudgetBufferRenderer.VERTEX_FORMAT_SIZE);
        try {
            // 顶点 0: 左下
            MemoryUtil.memPutFloat(vertexData + 0 * 24 + 0, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 0 * 24 + 4, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 0 * 24 + 8, 0.5f);
            MemoryUtil.memPutInt(vertexData + 0 * 24 + 12, 0);
            MemoryUtil.memPutFloat(vertexData + 0 * 24 + 16, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 0 * 24 + 20, 0.0f);

            // 顶点 1: 右下
            MemoryUtil.memPutFloat(vertexData + 1 * 24 + 0, 1.0f);
            MemoryUtil.memPutFloat(vertexData + 1 * 24 + 4, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 1 * 24 + 8, 0.5f);
            MemoryUtil.memPutInt(vertexData + 1 * 24 + 12, 0);
            MemoryUtil.memPutFloat(vertexData + 1 * 24 + 16, 0.0625f);
            MemoryUtil.memPutFloat(vertexData + 1 * 24 + 20, 0.0f);

            // 顶点 2: 右上
            MemoryUtil.memPutFloat(vertexData + 2 * 24 + 0, 1.0f);
            MemoryUtil.memPutFloat(vertexData + 2 * 24 + 4, 1.0f);
            MemoryUtil.memPutFloat(vertexData + 2 * 24 + 8, 0.5f);
            MemoryUtil.memPutInt(vertexData + 2 * 24 + 12, 0);
            MemoryUtil.memPutFloat(vertexData + 2 * 24 + 16, 0.0625f);
            MemoryUtil.memPutFloat(vertexData + 2 * 24 + 20, 0.0625f);

            // 顶点 3: 左上
            MemoryUtil.memPutFloat(vertexData + 3 * 24 + 0, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 3 * 24 + 4, 1.0f);
            MemoryUtil.memPutFloat(vertexData + 3 * 24 + 8, 0.5f);
            MemoryUtil.memPutInt(vertexData + 3 * 24 + 12, 0);
            MemoryUtil.memPutFloat(vertexData + 3 * 24 + 16, 0.0f);
            MemoryUtil.memPutFloat(vertexData + 3 * 24 + 20, 0.0625f);

            // 设置投影矩阵
            Matrix4f mat = new Matrix4f();
            mat.set(2, 0, 0, 0,
                    0, 2, 0, 0,
                    0, 0, -1f, 0,
                    -1, -1, 0, 1);

            BudgetBufferRenderer.setup(vertexData, 1, texId);
            BudgetBufferRenderer.render(mat);
        } finally {
            MemoryUtil.nmemFree(vertexData);
        }

        glBindVertexArray(0);
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        glFinish();

        int[] colourData = new int[this.width * this.height];
        int[] depthData = new int[this.width * this.height];
        glGetTextureImage(this.colourTex.id, 0, GL_RGBA, GL_UNSIGNED_BYTE, colourData);
        glGetTextureImage(this.depthTex.id, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, depthData);

        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        GL11C.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        int nonZeroCount = 0;
        for (int colour : colourData) {
            if (colour != 0) {
                nonZeroCount++;
            }
        }

        return new TestResult(
            "RenderQuad",
            colourData.length,
            nonZeroCount,
            nonZeroCount,
            new ColourDepthTextureData(colourData, depthData, width, height)
        );
    }

    /**
     * 测试4: 使用 ReuseVertexConsumer 渲染一个真实的方块
     */
    public TestResult testRenderBlock() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Must run on render thread");
        }

        int oldFB = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] oldViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, oldViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
        GL11C.glViewport(0, 0, this.width, this.height);

        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        int texId = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getId();

        BlockState state = Blocks.STONE.defaultBlockState();
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        ReuseVertexConsumer vc = new ReuseVertexConsumer();
        vc.reset();

        RandomSource rand = RandomSource.create(42L);
        int quadCount = 0;
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            rand.setSeed(42L);
            var quads = model.getQuads(state, direction, rand, ModelData.EMPTY, null);
            for (var quad : quads) {
                vc.quad(quad, 0);
                quadCount++;
            }
        }

        int nonZeroCount = 0;
        int[] colourData = new int[this.width * this.height];
        int[] depthData = new int[this.width * this.height];

        if (!vc.isEmpty()) {
            Matrix4f mat = new Matrix4f();
            mat.set(2, 0, 0, 0,
                    0, 2, 0, 0,
                    0, 0, -1f, 0,
                    -1, -1, 0, 1);

            BudgetBufferRenderer.setup(vc.getAddress(), vc.quadCount(), texId);
            BudgetBufferRenderer.render(mat);
            glBindVertexArray(0);

            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
            glFinish();

            glGetTextureImage(this.colourTex.id, 0, GL_RGBA, GL_UNSIGNED_BYTE, colourData);
            glGetTextureImage(this.depthTex.id, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, depthData);

            for (int colour : colourData) {
                if (colour != 0) {
                    nonZeroCount++;
                }
            }
        }

        vc.free();

        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        GL11C.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        return new TestResult(
            "RenderBlock(quads=" + quadCount + ",uvMin=" + String.format("%.4f", vc.getMinU()) + ")",
            colourData.length,
            nonZeroCount,
            nonZeroCount,
            new ColourDepthTextureData(colourData, depthData, width, height)
        );
    }

    /**
     * 测试3: 检查 framebuffer 完整性
     */
    public String testFramebufferStatus() {
        int oldFB = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);

        return switch (status) {
            case GL_FRAMEBUFFER_COMPLETE -> "COMPLETE";
            case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "INCOMPLETE_ATTACHMENT";
            case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "INCOMPLETE_MISSING_ATTACHMENT";
            case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "INCOMPLETE_DRAW_BUFFER";
            case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "INCOMPLETE_READ_BUFFER";
            case GL_FRAMEBUFFER_UNSUPPORTED -> "UNSUPPORTED";
            case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE -> "INCOMPLETE_MULTISAMPLE";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    @Override
    public void close() {
        this.framebuffer.free();
        this.colourTex.free();
        this.depthTex.free();
    }

    public record TestResult(String testName, int totalPixels, int nonZeroPixels, int expectedColorPixels,
                             ColourDepthTextureData texture) {
        public String summary() {
            return String.format("%s: total=%d, nonZero=%d, expected=%d",
                    testName, totalPixels, nonZeroPixels, expectedColorPixels);
        }
    }
}
