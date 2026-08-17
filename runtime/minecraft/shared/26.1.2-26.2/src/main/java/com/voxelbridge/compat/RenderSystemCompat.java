package com.voxelbridge.compat;

import com.voxelbridge.adapter.Adapters;

/**
 * Version-agnostic helpers for RenderSystem API drift.
 * Delegates to PlatformRenderHelper.
 */
public final class RenderSystemCompat {

    private RenderSystemCompat() {}

    public static boolean isOnRenderThread() {
        return Adapters.getPlatformRenderHelper().isOnRenderThread();
    }

    public static void recordRenderCall(Runnable task) {
        Adapters.getPlatformRenderHelper().recordRenderCall(task);
    }
}
