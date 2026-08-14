package com.voxelbridge.compat;

import net.minecraft.client.gui.GuiGraphics;

import com.voxelbridge.adapter.Adapters;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Version-agnostic helpers for GuiGraphics pose access.
 */
public final class GuiPoseCompat {

    // private static final Map<Class<?>, PoseOps> CACHE = new ConcurrentHashMap<>();

    private GuiPoseCompat() {}

    public static void push(GuiGraphics gfx) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().pushPose(pose);
    }

    public static void pop(GuiGraphics gfx) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().popPose(pose);
    }

    public static void translate(GuiGraphics gfx, float x, float y, float z) {
        if (gfx == null) return;
        var pose = Adapters.getPlatformRenderHelper().getGuiPose(gfx);
        Adapters.getPlatformRenderHelper().translatePose(pose, x, y, z);
    }
}
