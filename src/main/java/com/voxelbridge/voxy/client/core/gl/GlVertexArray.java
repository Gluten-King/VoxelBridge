package com.voxelbridge.voxy.client.core.gl;

import com.voxelbridge.voxy.common.util.TrackedObject;

import java.util.Arrays;

import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL45C.*;

public class GlVertexArray extends TrackedObject {
    public static final int STATIC_VAO = glGenVertexArrays();

    public final int id;
    private int[] indices = new int[0];
    private int stride;
    public GlVertexArray() {
        this.id = glCreateVertexArrays();
    }

    @Override
    public void free() {
        this.free0();
        glDeleteVertexArrays(this.id);
    }

    public void bind() {
        glBindVertexArray(this.id);
    }

    public GlVertexArray bindBuffer(int buffer) {
        // All attributes use binding point 0, so only bind buffer to binding point 0
        glVertexArrayVertexBuffer(this.id, 0, buffer, 0, this.stride);
        return this;
    }

    public GlVertexArray bindElementBuffer(int buffer) {
        glVertexArrayElementBuffer(this.id, buffer);
        return this;
    }

    public GlVertexArray setStride(int stride) {
        this.stride = stride;
        return this;
    }

    public GlVertexArray setI(int index, int type, int count, int offset) {
        this.addIndex(index);
        glEnableVertexArrayAttrib(this.id, index);
        glVertexArrayAttribIFormat(this.id, index, count, type, offset);
        glVertexArrayAttribBinding(this.id, index, 0);  // All attributes use binding point 0
        return this;
    }

    public GlVertexArray setF(int index, int type, int count, int offset) {
        return this.setF(index, type, count, false, offset);
    }

    public GlVertexArray setF(int index, int type, int count, boolean normalize, int offset) {
        this.addIndex(index);
        glEnableVertexArrayAttrib(this.id, index);
        glVertexArrayAttribFormat(this.id, index, count, type, normalize, offset);
        glVertexArrayAttribBinding(this.id, index, 0);  // All attributes use binding point 0
        return this;
    }

    private void addIndex(int index) {
        for (int i : this.indices) {
            if (i == index) {
                return;
            }
        }
        this.indices = Arrays.copyOf(this.indices, this.indices.length+1);
        this.indices[this.indices.length-1] = index;
    }
}

