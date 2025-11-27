package com.voxelbridge.util;

import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;


@OnlyIn(Dist.CLIENT)
public final class ProgressNotifier {

    private ProgressNotifier() {}

    public static void show(Minecraft mc, double percent, int processed, int total) {
        if (mc == null || mc.player == null || total <= 0) {
            return;
        }
        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            String text = String.format("[VoxelBridge] Export progress: %.1f%% (%d/%d chunks)",
                    percent, processed, total);
            mc.player.displayClientMessage(Component.literal(text), true);
        });
    }

    public static void showDetailed(Minecraft mc, ExportProgressTracker.Progress progress) {
        if (mc == null || mc.player == null || progress.total() <= 0) {
            return;
        }
        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            text.append(String.format("[VoxelBridge] Export: %.1f%% (%d/%d)",
                progress.percent(), progress.done(), progress.total()));

            if (progress.failed() > 0) {
                text.append(String.format(" | Failed: %d", progress.failed()));
            }

            mc.player.displayClientMessage(Component.literal(text.toString()), true);
        });
    }
}
