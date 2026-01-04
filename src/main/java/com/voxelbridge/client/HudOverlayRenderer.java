package com.voxelbridge.client;

import com.voxelbridge.util.client.ProgressNotifier;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@OnlyIn(Dist.CLIENT)
public final class HudOverlayRenderer {

    private HudOverlayRenderer() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ProgressNotifier.renderOverlay(mc, event.getGuiGraphics());
    }
}
