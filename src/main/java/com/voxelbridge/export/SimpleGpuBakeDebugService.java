package com.voxelbridge.export;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.voxelbridge.voxy.client.core.gl.GlFramebuffer;
import com.voxelbridge.voxy.client.core.gl.GlTexture;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45C.glGetTextureImage;

/**
 * 简化版 GPU Bake 调试服务 - 直接移植自 voxy-master 的实现
 * 使用 Minecraft 原生渲染系统，同步读取结果
 */
public final class SimpleGpuBakeDebugService implements AutoCloseable {
    private static final List<PoseStack> FACE_VIEWS = new ArrayList<>();

    private final int width;
    private final int height;
    private final GlTexture colourTex;
    private final GlTexture depthTex;
    private final GlFramebuffer framebuffer;

    static {
        addView(-90, 0, 0);    // DOWN
        addView(90, 0, 0);     // UP
        addView(0, 180, 0);    // NORTH
        addView(0, 0, 0);      // SOUTH
        addView(0, 90, 270);   // WEST
        addView(0, 270, 270);  // EAST
    }

    private static void addView(float pitch, float yaw, float rotation) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.translate(-0.5f, -0.5f, -0.5f);
        FACE_VIEWS.add(stack);
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(
                vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                (float) Math.cos(hangle)
        );
    }

    public SimpleGpuBakeDebugService(int size) {
        this.width = size;
        this.height = size;
        this.colourTex = new GlTexture().store(GL_RGBA8, 1, size, size).name("SimpleBakeColour");
        this.depthTex = new GlTexture().store(GL_DEPTH24_STENCIL8, 1, size, size).name("SimpleBakeDepth");
        this.framebuffer = new GlFramebuffer()
                .bind(GL_COLOR_ATTACHMENT0, this.colourTex)
                .bind(GL_DEPTH_STENCIL_ATTACHMENT, this.depthTex)
                .verify()
                .name("SimpleBakeFramebuffer");
    }

    public int getSize() {
        return width;
    }

    public BakeResult bake(BlockState state) {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("SimpleGpuBakeDebugService.bake must run on the render thread");
        }

        boolean isFluid = state.getBlock() instanceof LiquidBlock;

        // 保存旧状态
        int oldFB = GlStateManager._getInteger(GL_FRAMEBUFFER_BINDING);
        int[] oldViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, oldViewport);

        // 设置 viewport
        GL11C.glViewport(0, 0, this.width, this.height);

        // 获取渲染层
        RenderType renderLayer;
        if (isFluid) {
            renderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            renderLayer = ItemBlockRenderTypes.getChunkRenderType(state);
        }

        // 设置 GL 状态
        glClearColor(0, 0, 0, 0);
        glClearDepth(1);
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);

        glEnable(GL_STENCIL_TEST);
        glDepthRange(0, 1);
        glDepthMask(true);
        glEnable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);

        if (renderLayer == RenderType.translucent()) {
            glBlendFuncSeparate(GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }

        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilMask(0xFF);

        // 获取纹理
        int texId = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getId();

        // 获取模型
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        // 渲染每个面
        ColourDepthTextureData[] faces = new ColourDepthTextureData[6];
        for (int i = 0; i < 6; i++) {
            faces[i] = captureView(state, model, FACE_VIEWS.get(i), i, isFluid, texId, renderLayer);
        }

        // 恢复状态
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, oldFB);
        GL11C.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);

        return new BakeResult(faces, 0, null);
    }

    private ColourDepthTextureData captureView(BlockState state, net.minecraft.client.resources.model.BakedModel model,
                                               PoseStack viewStack, int face, boolean isFluid, int textureId,
                                               RenderType renderLayer) {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        // 设置投影矩阵
        Matrix4f projection = new Matrix4f().set(new float[]{
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -1f, 0,
                -1, -1, 0, 1,
        });

        Matrix4f mvp = new Matrix4f(projection).mul(viewStack.last().pose());

        // 使用 Minecraft 的 Tessellator 和 BufferBuilder
        var tesselator = Tesselator.getInstance();

        // 设置着色器
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);

        // 开始构建顶点
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        if (!isFluid) {
            renderQuads(builder, state, model, viewStack);
        } else {
            renderFluid(builder, state, face);
        }

        // 渲染
        try {
            MeshData meshData = builder.build();
            if (meshData != null) {
                // 手动设置 MVP 矩阵
                RenderSystem.getModelViewStack().pushMatrix();
                RenderSystem.getModelViewStack().identity();
                RenderSystem.getModelViewStack().mul(mvp);
                RenderSystem.applyModelViewMatrix();

                BufferUploader.drawWithShader(meshData);

                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        } catch (Exception e) {
            System.err.println("Got empty buffer builder for block " + state + ": " + e.getMessage());
        }

        // 读取结果
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        int[] colourData = new int[this.width * this.height];
        int[] depthData = new int[this.width * this.height];
        glGetTextureImage(this.colourTex.id, 0, GL_RGBA, GL_UNSIGNED_BYTE, colourData);
        glGetTextureImage(this.depthTex.id, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, depthData);

        return new ColourDepthTextureData(colourData, depthData, this.width, this.height);
    }

    private void renderQuads(BufferBuilder builder, BlockState state,
                             net.minecraft.client.resources.model.BakedModel model, PoseStack stack) {
        RandomSource random = RandomSource.create(42L);
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            random.setSeed(42L);
            var quads = model.getQuads(state, direction, random, ModelData.EMPTY, null);
            for (BakedQuad quad : quads) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                int uvOffset = 4; // BLOCK format: pos(3) + color(1) + uv(2) + ...

                for (int i = 0; i < 4; i++) {
                    int base = i * stride;
                    float x = Float.intBitsToFloat(vertices[base]);
                    float y = Float.intBitsToFloat(vertices[base + 1]);
                    float z = Float.intBitsToFloat(vertices[base + 2]);
                    float u = Float.intBitsToFloat(vertices[base + uvOffset]);
                    float v = Float.intBitsToFloat(vertices[base + uvOffset + 1]);

                    // 应用变换
                    var transformed = stack.last().pose().transformPosition(x, y, z, new org.joml.Vector3f());

                    builder.addVertex(transformed.x, transformed.y, transformed.z)
                            .setUv(u, v)
                            .setColor(255, 255, 255, 255);
                }
            }
        }
    }

    private void renderFluid(BufferBuilder builder, BlockState state, int face) {
        Minecraft.getInstance().getBlockRenderer().renderLiquid(
                BlockPos.ZERO,
                new BlockAndTintGetter() {
                    @Override
                    public float getShade(Direction direction, boolean shaded) { return 1.0f; }
                    @Override
                    public LevelLightEngine getLightEngine() { return null; }
                    @Override
                    public int getBrightness(LightLayer type, BlockPos pos) { return 15; }
                    @Override
                    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) { return 0xFFFFFF; }
                    @Nullable @Override
                    public BlockEntity getBlockEntity(BlockPos pos) { return null; }
                    @Override
                    public BlockState getBlockState(BlockPos pos) {
                        if (pos.equals(Direction.from3DDataValue(face).getNormal())) {
                            return Blocks.AIR.defaultBlockState();
                        }
                        return state;
                    }
                    @Override
                    public FluidState getFluidState(BlockPos pos) {
                        if (pos.equals(Direction.from3DDataValue(face).getNormal())) {
                            return Blocks.AIR.defaultBlockState().getFluidState();
                        }
                        return state.getFluidState();
                    }
                    @Override
                    public int getHeight() { return 256; }
                    @Override
                    public int getMinBuildHeight() { return 0; }
                },
                builder,
                state,
                state.getFluidState()
        );
    }

    @Override
    public void close() {
        this.framebuffer.free();
        this.colourTex.free();
        this.depthTex.free();
    }

    public record BakeResult(ColourDepthTextureData[] textures, int flags,
                             com.voxelbridge.voxy.client.core.model.bakery.ModelTextureBakery.UvStats uvStats) {}
}
