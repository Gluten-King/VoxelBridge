package com.voxelbridge.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.voxelbridge.voxy.client.core.gl.GlBuffer;
import com.voxelbridge.voxy.client.core.gl.GlVertexArray;
import com.voxelbridge.voxy.client.core.gl.shader.Shader;
import com.voxelbridge.voxy.client.core.gl.shader.ShaderType;
import com.voxelbridge.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL45.*;

public class BudgetBufferRenderer {
    public static final int VERTEX_FORMAT_SIZE = 24;

    private static final Shader bakeryShader = Shader.make()
            .add(ShaderType.VERTEX, "voxy:bakery/position_tex.vsh")
            .add(ShaderType.FRAGMENT, "voxy:bakery/position_tex.fsh")
            .compile();


    public static void init(){}
    private static final GlBuffer indexBuffer;
    static {
        int indexCount = 4096 * 6;
        int byteSize = indexCount * 2;
        indexBuffer = new GlBuffer(byteSize, 0, false);
        var indices = MemoryUtil.memAllocShort(indexCount);
        for (int i = 0; i < 4096; i++) {
            int base = i * 4;
            indices.put((short) base);
            indices.put((short) (base + 1));
            indices.put((short) (base + 2));
            indices.put((short) (base + 2));
            indices.put((short) (base + 3));
            indices.put((short) base);
        }
        indices.flip();
        glNamedBufferSubData(indexBuffer.id, 0, indices);
        MemoryUtil.memFree(indices);
    }

    private static final int STRIDE = 24;
    private static final GlVertexArray VA = new GlVertexArray()
            .setStride(STRIDE)
            .setF(0, GL_FLOAT, 4, 0)//pos, metadata
            .setF(1, GL_FLOAT, 2, 4 * 4)//UV
            .bindElementBuffer(indexBuffer.id);

    private static GlBuffer immediateBuffer;
    private static int quadCount;
    public static void drawFast(MeshData buffer, int texId, Matrix4f matrix) {
        if (buffer.drawState().mode() != VertexFormat.Mode.QUADS) {
            throw new IllegalStateException("Fast only supports quads");
        }

        var buff = buffer.vertexBuffer();
        int size = buff.remaining();
        if (size%STRIDE != 0) throw new IllegalStateException();
        size /= STRIDE;
        if (size%4 != 0) throw new IllegalStateException();
        size /= 4;
        setup(MemoryUtil.memAddress(buff), size, texId);
        buffer.close();

        render(matrix);
    }

    public static void setup(long dataPtr, int quads, int texId) {
        // Clear any pre-existing OpenGL errors to avoid false positives
        while (org.lwjgl.opengl.GL11.glGetError() != org.lwjgl.opengl.GL11.GL_NO_ERROR);

        if (quads == 0) {
            throw new IllegalStateException();
        }

        quadCount = quads;

        long size = quads * 4L * STRIDE;
        if (immediateBuffer == null || immediateBuffer.size()<size) {
            if (immediateBuffer != null) {
                immediateBuffer.free();
            }
            immediateBuffer = new GlBuffer(size*2L);//This also accounts for when immediateBuffer == null
            VA.bindBuffer(immediateBuffer.id);
        }
        long ptr = UploadStream.INSTANCE.upload(immediateBuffer, 0, size);
        MemoryUtil.memCopy(dataPtr, ptr, size);
        UploadStream.INSTANCE.commit();

        bakeryShader.bind();
        VA.bind();
        glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        glBindSampler(0, 0);
        glBindTextureUnit(0, texId);
    }

    public static void render(Matrix4f matrix) {
        glUniformMatrix4fv(1, false, matrix.get(new float[16]));
        glDrawElements(GL_TRIANGLES, quadCount * 2 * 3, GL_UNSIGNED_SHORT, 0);
    }
}

