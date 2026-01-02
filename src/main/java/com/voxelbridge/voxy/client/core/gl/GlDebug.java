package com.voxelbridge.voxy.client.core.gl;

import com.voxelbridge.voxy.client.core.gl.shader.Shader;

import static org.lwjgl.opengl.GL43C.*;

public class GlDebug {
    public static final boolean GL_DEBUG = System.getProperty("voxy.glDebug", "false").equals("true");
    private static volatile boolean FORCE_DEBUG_GROUPS = false;


    public static void push() {
        //glPushDebugGroup()
    }

    public static void setForceDebugGroups(boolean enable) {
        FORCE_DEBUG_GROUPS = enable;
    }

    public static void pushGroup(String label) {
        if (!(GL_DEBUG || FORCE_DEBUG_GROUPS)) {
            return;
        }
        glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, label);
    }

    public static void popGroup() {
        if (!(GL_DEBUG || FORCE_DEBUG_GROUPS)) {
            return;
        }
        glPopDebugGroup();
    }

    public static GlBuffer name(String name, GlBuffer buffer) {
        if (GL_DEBUG) {
            glObjectLabel(GL_BUFFER, buffer.id, name);
        }
        return buffer;
    }

    public static <T extends Shader> T name(String name, T shader) {
        if (GL_DEBUG) {
            glObjectLabel(GL_PROGRAM, shader.id(), name);
        }
        return shader;
    }

    public static GlFramebuffer name(String name, GlFramebuffer framebuffer) {
        if (GL_DEBUG) {
            glObjectLabel(GL_FRAMEBUFFER, framebuffer.id, name);
        }
        return framebuffer;
    }

    public static GlTexture name(String name, GlTexture texture) {
        if (GL_DEBUG) {
            glObjectLabel(GL_TEXTURE, texture.id, name);
        }
        return texture;
    }

    public static GlPersistentMappedBuffer name(String name, GlPersistentMappedBuffer buffer) {
        if (GL_DEBUG) {
            glObjectLabel(GL_BUFFER, buffer.id, name);
        }
        return buffer;
    }
}

