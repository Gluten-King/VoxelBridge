package com.voxelbridge.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.voxelbridge.adapter.Adapters;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Version-agnostic helpers for GuiGraphicsExtractor pose access.
 */
public final class GuiPoseCompat {

    // private static final Map<Class<?>, PoseOps> CACHE = new ConcurrentHashMap<>();

    private GuiPoseCompat() {}

    public static void push(GuiGraphicsExtractor gfx) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().pushPose(pose);
    }

    public static void pop(GuiGraphicsExtractor gfx) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().popPose(pose);
    }

    public static void translate(GuiGraphicsExtractor gfx, float x, float y, float z) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().translatePose(pose, x, y, z);
    }
}
