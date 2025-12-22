package com.voxelbridge.util.client;

import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side progress notifications: action bar text + HUD progress bar.
 */
public final class ProgressNotifier {

    private static ExportProgressTracker.Progress lastProgress;
    private static long lastProgressNanos = 0L;

    private ProgressNotifier() {}

    public static void show(Minecraft mc, double percent, int processed, int total) {
        if (mc == null || mc.player == null || total <= 0) {
            return;
        }
        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            String format = ExportProgressTracker.getActiveFormat().getDescription();
            String text = String.format("[VoxelBridge %s] Export progress: %.1f%% (%d/%d chunks)",
                    format, percent, processed, total);
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
            mc.player.displayClientMessage(buildStatus(progress), true);
            lastProgress = progress;
            lastProgressNanos = System.nanoTime();
        });
    }

    private static Component buildStatus(ExportProgressTracker.Progress p) {
        String format = ExportProgressTracker.getActiveFormat().getDescription();
        String stage = stageBase(p.stage());
        String prf = p.stage() == ExportProgressTracker.Stage.SAMPLING
                ? String.format(" | P:%d R:%d F:%d", p.pending(), p.running(), p.failed())
                : "";
        String eta = eta(p);
        String timing = eta.isEmpty()
                ? String.format("%.1fs", p.elapsedSeconds())
                : String.format("%.1fs %s", p.elapsedSeconds(), eta);

        MutableComponent text = Component.empty()
                .append(Component.literal("[" + format + "] ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(stage).withStyle(stageTextColor(p.stage())))
                .append(Component.literal(String.format(" %.1f%%", p.displayPercent())).withStyle(ChatFormatting.YELLOW));

        if (!prf.isEmpty()) {
            text = text
                .append(Component.literal(" P:").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(p.pending())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" R:").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(p.running())).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" F:").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(p.failed())).withStyle(ChatFormatting.RED));
        }

        text = text
                .append(Component.literal(" | " + timing).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | " + memoryStats()).withStyle(ChatFormatting.GREEN));
        return text;
    }

    private static String eta(ExportProgressTracker.Progress p) {
        int completed = p.done() + p.failed();
        if (completed == 0 || p.total() == 0) return "";
        double rate = completed / Math.max(0.1, p.elapsedSeconds());
        int remaining = p.total() - completed;
        double etaSec = remaining / Math.max(0.1, rate);
        return String.format("ETA %.1fs", etaSec);
    }

    private static String memoryStats() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double usedMb = used / 1024.0 / 1024.0;
        double maxMb = max / 1024.0 / 1024.0;
        return String.format("Mem %d/%dMB", Math.round(usedMb), Math.round(maxMb));
    }

    private static String stageLabel(ExportProgressTracker.Stage stage, String detail) {
        return (detail != null && !detail.isEmpty()) ? detail : stageBase(stage);
    }

    public static void renderOverlay(Minecraft mc, GuiGraphics gfx) {
        if (lastProgress == null || mc == null) {
            return;
        }
        if (lastProgress.stage() == ExportProgressTracker.Stage.COMPLETE) {
            long elapsedNs = System.nanoTime() - lastProgressNanos;
            if (elapsedNs > 1_000_000_000L) {
                lastProgress = null;
                return;
            }
        }

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int barWidth = 182;
        int barHeight = 6;
        int x = (screenW - barWidth) / 2;
        int y = screenH - 36; // above XP bar

        float pct = Math.max(0f, Math.min(1f, lastProgress.percent() / 100f));
        float dispPct = Math.max(0f, Math.min(1f, lastProgress.displayPercent() / 100f));
        int filled = Math.round(barWidth * dispPct);

        // background
        gfx.fill(x, y, x + barWidth, y + barHeight, 0xAA000000);
        // progress
        gfx.fill(x, y, x + filled, y + barHeight, stageBarColor(lastProgress.stage()));

        String title = String.format("[%s] %s %.1f%%",
                ExportProgressTracker.getActiveFormat().getDescription(),
                stageLabel(lastProgress.stage(), lastProgress.stageDetail()),
                lastProgress.displayPercent());
        int titleWidth = mc.font.width(title);
        int titleColor = stageBarColor(lastProgress.stage());
        gfx.drawString(mc.font, title, (screenW - titleWidth) / 2, y - 10, titleColor, false);
    }

    private static String stageBase(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> "Sampling";
            case ATLAS -> "Atlas";
            case FINALIZE -> "Finalize";
            case COMPLETE -> "Complete";
            default -> "Preparing";
        };
    }

    private static ChatFormatting stageTextColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> ChatFormatting.BLUE;
            case ATLAS -> ChatFormatting.AQUA;
            case FINALIZE -> ChatFormatting.GOLD;
            case COMPLETE -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
    }

    private static int stageBarColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> 0xFF4DA3FF;   // blue-ish
            case ATLAS -> 0xFF00D4C0;     // teal
            case FINALIZE -> 0xFFF0C050;  // gold
            case COMPLETE -> 0xFF65D96A;  // green
            default -> 0xFFCCCCCC;
        };
    }
}
