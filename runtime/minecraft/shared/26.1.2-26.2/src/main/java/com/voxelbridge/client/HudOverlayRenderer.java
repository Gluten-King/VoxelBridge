package com.voxelbridge.client;

import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class HudOverlayRenderer {

    private HudOverlayRenderer() {}

    public static void render(GuiGraphicsExtractor guiGraphics) {
        var mc = ClientAccessHolder.get().getMinecraft();
        ProgressNotifier.renderOverlay(mc, guiGraphics);
    }
}
