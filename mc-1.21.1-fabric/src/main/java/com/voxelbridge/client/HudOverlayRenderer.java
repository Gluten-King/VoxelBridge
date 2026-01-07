package com.voxelbridge.client;

import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class HudOverlayRenderer {
    private HudOverlayRenderer() {}

    /**
     * Fabric HUD render callback.
     */
    public static void onRenderGui(DrawContext drawContext, float tickDelta, MinecraftClient mc) {
        if (mc == null) return;
        ExportProgressTracker.Progress p = ExportProgressTracker.progress();
        if (p.total() <= 0) return;
        String text = String.format("[VoxelBridge] Export %d/%d (%.1f%%)", p.done(), p.total(), p.percent());
        int w = mc.textRenderer.getWidth(text);
        int x = (mc.getWindow().getScaledWidth() - w) / 2;
        int y = 12;
        drawContext.drawText(mc.textRenderer, Text.literal(text), x, y, 0xFFFFFF, true);
    }
}
