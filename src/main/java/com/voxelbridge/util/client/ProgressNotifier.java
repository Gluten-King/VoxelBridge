package com.voxelbridge.util.client;

import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.ChatFormatting;
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
            Component msg = buildRichProgress(progress);
            mc.player.displayClientMessage(msg, true);
        });
    }

    private static Component buildRichProgress(ExportProgressTracker.Progress p) {
        String bar = buildBar(p.percent());
        String mem = memoryStats();
        String eta = eta(p);
        String stage = stageLabel(p.stage(), p.stageDetail());
        String format = ExportProgressTracker.getActiveFormat().getDescription();

        String line1 = String.format("[VoxelBridge %s] %s %.1f%% %s", format, stage, p.percent(), bar);
        String line2 = String.format("完成:%d 运行:%d 待:%d 失败:%d | %.1fs%s | %s",
            p.done(), p.running(), p.pending(), p.failed(), p.elapsedSeconds(), eta, mem);

        return Component.literal(line1 + "\n" + line2).withStyle(ChatFormatting.RESET);
    }

    private static String buildBar(float percent) {
        int segments = 10;
        int filled = Math.round(percent / 100f * segments);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            sb.append(i < filled ? '=' : '.');
        }
        return "[" + sb + "]";
    }

    private static String memoryStats() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double usedMb = used / 1024.0 / 1024.0;
        double maxMb = max / 1024.0 / 1024.0;
        return String.format("Mem %d/%dMB", Math.round(usedMb), Math.round(maxMb));
    }

    private static String eta(ExportProgressTracker.Progress p) {
        int completed = p.done() + p.failed();
        if (completed == 0 || p.total() == 0) return "";
        double rate = completed / Math.max(0.1, p.elapsedSeconds());
        int remaining = p.total() - completed;
        double etaSec = remaining / Math.max(0.1, rate);
        return String.format(" ETA %.1fs", etaSec);
    }

    private static String stageLabel(ExportProgressTracker.Stage stage, String detail) {
        String base = switch (stage) {
            case SAMPLING -> "采样";
            case ATLAS -> "贴图";
            case FINALIZE -> "写出";
            case COMPLETE -> "完成";
            default -> "准备";
        };
        if (detail != null && !detail.isEmpty()) {
            return base + " - " + detail;
        }
        return base;
    }
}
