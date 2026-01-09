package com.voxelbridge.client;

import com.voxelbridge.util.client.ProgressNotifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public final class HudOverlayRenderer {
    private HudOverlayRenderer() {}

    /**
     * Fabric HUD render callback.
     */
    public static void onRenderGui(DrawContext drawContext, RenderTickCounter tickCounter, MinecraftClient mc) {
        if (mc == null) {
            return;
        }
        ProgressNotifier.renderOverlay(mc, drawContext);
    }
}
