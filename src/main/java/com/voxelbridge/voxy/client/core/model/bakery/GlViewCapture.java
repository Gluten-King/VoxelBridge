package com.voxelbridge.voxy.client.core.model.bakery;

import com.voxelbridge.voxy.client.core.gl.GlFramebuffer;
import com.voxelbridge.voxy.client.core.gl.GlTexture;
import com.voxelbridge.voxy.client.core.gl.shader.Shader;
import com.voxelbridge.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_PIXEL_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_TEXTURE_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;

public class GlViewCapture {
    private static final int LOCAL_SIZE_X = 16;
    private static final int LOCAL_SIZE_Y = 16;

    private final int width;
    private final int height;
    private final int tilesX;
    private final int tilesY;
    private final GlTexture colourTex;
    private final GlTexture depthTex;
    private final GlTexture stencilTex;
    final GlFramebuffer framebuffer;
    private final Shader copyOutShader;

    public GlViewCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.tilesX = (width + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X;
        this.tilesY = (height + LOCAL_SIZE_Y - 1) / LOCAL_SIZE_Y;
        this.colourTex = new GlTexture().store(GL_RGBA8, 1, width * 3, height * 2).name("ModelBakeryColour");
        this.depthTex = new GlTexture().store(GL_DEPTH24_STENCIL8, 1, width * 3, height * 2).name("ModelBakeryDepth");
        glTextureParameteri(this.depthTex.id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_STENCIL_INDEX);
        this.stencilTex = this.depthTex.createView();
        glTextureParameteri(this.depthTex.id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);

        this.framebuffer = new GlFramebuffer()
                .bind(GL_COLOR_ATTACHMENT0, this.colourTex)
                .setDrawBuffers(GL_COLOR_ATTACHMENT0)
                .bind(GL_DEPTH_STENCIL_ATTACHMENT, this.depthTex)
                .verify()
                .name("ModelFramebuffer");

        glTextureParameteri(this.stencilTex.id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_STENCIL_INDEX);
        glTextureParameteri(this.stencilTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.stencilTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);

        glTextureParameteri(this.depthTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.depthTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);

        this.copyOutShader = Shader.makeAuto()
                .define("WIDTH", width)
                .define("HEIGHT", height)
                .define("LOCAL_SIZE_X", LOCAL_SIZE_X)
                .define("LOCAL_SIZE_Y", LOCAL_SIZE_Y)
                .define("COLOUR_IN_BINDING", 0)
                .define("DEPTH_IN_BINDING", 1)
                .define("STENCIL_IN_BINDING", 2)
                .define("BUFFER_OUT_BINDING", 4)
                .add(ShaderType.COMPUTE, "voxy:bakery/bufferreorder.comp")
                .compile()
                .name("ModelBakeryOut")
                .texture("COLOUR_IN_BINDING", 0, this.colourTex)
                .texture("DEPTH_IN_BINDING", 0, this.depthTex)
                .texture("STENCIL_IN_BINDING", 0, this.stencilTex);
    }

    public void emitToStream(int buffer, int offset) {
        this.copyOutShader.bind();
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 4, buffer, offset,
                (this.width * 3L) * (this.height * 2L) * 4L * 2L);
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT |
                GL_PIXEL_BUFFER_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        glDispatchCompute(this.tilesX * 3, this.tilesY * 2, 1);
        // 确保 compute shader 的 SSBO 写入对后续读取可见
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 4, 0, 0, 4);
    }

    public void clear() {
        try (var stack = MemoryStack.stackPush()) {
            long ptr = stack.nmalloc(4 * 4);
            MemoryUtil.memPutLong(ptr, 0);
            MemoryUtil.memPutLong(ptr + 8, 0);
            nglClearNamedFramebufferfv(this.framebuffer.id, GL_COLOR, 0, ptr);
        }
        glClearNamedFramebufferfi(this.framebuffer.id, GL_DEPTH_STENCIL, 0, 1.0f, 0);
    }

    public void free() {
        this.framebuffer.free();
        this.colourTex.free();
        this.stencilTex.free();
        this.depthTex.free();
        this.copyOutShader.free();
    }
}
