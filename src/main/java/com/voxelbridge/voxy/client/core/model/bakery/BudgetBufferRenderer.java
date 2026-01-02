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
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.*;

public class BudgetBufferRenderer {
    public static final int VERTEX_FORMAT_SIZE = 28;

    private static final Shader bakeryShader = Shader.make()
            .add(ShaderType.VERTEX, "voxy:bakery/position_tex.vsh")
            .add(ShaderType.FRAGMENT, "voxy:bakery/position_tex.fsh")
            .compile();

    public static void init() {}

    private static final int INDEX_QUAD_COUNT = 4096;
    private static final GlBuffer indexBuffer;
    private static volatile boolean indexBufferInitialized = false;
    static {
        int indexCount = INDEX_QUAD_COUNT * 6;
        int byteSize = indexCount * 2;
        indexBuffer = new GlBuffer(byteSize, GL_DYNAMIC_STORAGE_BIT, false);
    }

    private static final int STRIDE = 28;
    private static final GlVertexArray VA = new GlVertexArray()
            .setStride(STRIDE)
            .setF(0, GL_FLOAT, 4, 0) // pos, metadata
            .setF(1, GL_FLOAT, 2, 4 * 4) // UV
            .setF(2, GL_UNSIGNED_BYTE, 4, true, 24) // Color
            .bindElementBuffer(indexBuffer.id);

    private static GlBuffer immediateBuffer;
    private static int quadCount;

    private static void ensureIndexBuffer() {
        if (indexBufferInitialized) {
            return;
        }
        int indexCount = INDEX_QUAD_COUNT * 6;
        var indices = MemoryUtil.memAllocShort(indexCount);
        for (int i = 0; i < INDEX_QUAD_COUNT; i++) {
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
        indexBufferInitialized = true;
    }

    public static void drawFast(MeshData buffer, int texId, Matrix4f matrix) {
        if (buffer.drawState().mode() != VertexFormat.Mode.QUADS) {
            throw new IllegalStateException("Fast only supports quads");
        }

        var buff = buffer.vertexBuffer();
        int size = buff.remaining();
        if (size % STRIDE != 0) throw new IllegalStateException();
        size /= STRIDE;
        if (size % 4 != 0) throw new IllegalStateException();
        size /= 4;
        setup(MemoryUtil.memAddress(buff), size, texId);
        buffer.close();

        render(matrix);
    }

    public static void setup(long dataPtr, int quads, int texId) {
        if (quads == 0) {
            throw new IllegalStateException();
        }

        ensureIndexBuffer();
        quadCount = quads;

        long size = quads * 4L * STRIDE;
        if (immediateBuffer == null || immediateBuffer.size() < size) {
            if (immediateBuffer != null) {
                immediateBuffer.free();
            }
            immediateBuffer = new GlBuffer(size * 2L);
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
