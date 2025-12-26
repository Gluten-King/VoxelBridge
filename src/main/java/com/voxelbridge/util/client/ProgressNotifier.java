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
            String format = ExportProgressTracker.getFormatLabel();
            // Enhanced Action Bar: [VoxelBridge] 50.0% (Sampling) | Chunks: 10/20
            String text = String.format("[VoxelBridge] %.1f%% (%s) | Chunks: %d/%d",
                    percent, format, processed, total);
            mc.player.displayClientMessage(Component.literal(text), true);
        });
    }

    public static void showDetailed(Minecraft mc, ExportProgressTracker.Progress progress) {
        if (mc == null || mc.player == null || progress.total() <= 0) {
            return;
        }
        // Update internal state only, do not spam Action Bar
        mc.execute(() -> {
            lastProgress = progress;
            lastProgressNanos = System.nanoTime();
        });
    }

    // buildStatus removed as it's no longer used for Action Bar

    private static String eta(ExportProgressTracker.Progress p) {
        int completed = p.done() + p.failed();
        if (completed == 0 || p.total() == 0) return "";
        double rate = completed / Math.max(0.1, p.elapsedSeconds());
        int remaining = p.total() - completed;
        double etaSec = remaining / Math.max(0.1, rate);
        return String.format("ETA: %.1fs", etaSec);
    }

    private static String cachedMemStats = "";
    private static long lastMemUpdate = 0L;

    private static String memoryStats() {
        long now = System.currentTimeMillis();
        if (now - lastMemUpdate < 500) { // Update every 500ms
            return cachedMemStats;
        }
        lastMemUpdate = now;

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double usedMb = used / 1024.0 / 1024.0;
        double maxMb = max / 1024.0 / 1024.0;
        cachedMemStats = String.format("%d/%dMB", Math.round(usedMb), Math.round(maxMb));
        return cachedMemStats;
    }

    private static boolean isHighMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return (double) used / max > 0.85; // >85% usage
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
        // Move to TOP of screen (Boss Bar position)
        int barWidth = 182;
        int barHeight = 6;
        int x = (screenW - barWidth) / 2;
        int y = 12; // Top offset

        float dispPct = Math.max(0f, Math.min(1f, lastProgress.displayPercent() / 100f));
        int filled = Math.round(barWidth * dispPct);

        // Raise Z-level to render above everything
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 1000.0f);

        // Outline (Black border)
        gfx.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0xFF000000);
        // Background
        gfx.fill(x, y, x + barWidth, y + barHeight, 0xFF444444);
        // Progress
        gfx.fill(x, y, x + filled, y + barHeight, stageBarColor(lastProgress.stage()));

        // Line 1: Title
        String title = String.format("[%s] %s %.1f%%",
                ExportProgressTracker.getFormatLabel(),
                stageLabel(lastProgress.stage(), lastProgress.stageDetail()),
                lastProgress.displayPercent());
        int titleWidth = mc.font.width(title);
        int titleColor = stageBarColor(lastProgress.stage());
        gfx.drawString(mc.font, title, (screenW - titleWidth) / 2, y + 8, titleColor, true);

        // Line 2: Colorful Details
        MutableComponent details = Component.empty();
        
        if (lastProgress.stage() == ExportProgressTracker.Stage.SAMPLING) {
            details.append(Component.literal("Chunks: ").withStyle(ChatFormatting.AQUA))
                   .append(Component.literal(String.format("%d/%d", lastProgress.done() + lastProgress.failed(), lastProgress.total()))
                           .withStyle(ChatFormatting.WHITE));
        }

        String etaStr = eta(lastProgress);
        if (!etaStr.isEmpty()) {
            if (!details.getString().isEmpty()) details.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
            details.append(Component.literal("ETA: ").withStyle(ChatFormatting.GOLD))
                   .append(Component.literal(etaStr.replace("ETA: ", "")).withStyle(ChatFormatting.YELLOW));
        }

        if (!details.getString().isEmpty()) details.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        
        ChatFormatting memColor = isHighMemory() ? ChatFormatting.RED : ChatFormatting.GREEN;
        details.append(Component.literal("Mem: ").withStyle(ChatFormatting.LIGHT_PURPLE))
               .append(Component.literal(memoryStats()).withStyle(memColor));
        
        int detailWidth = mc.font.width(details);
        gfx.drawString(mc.font, details, (screenW - detailWidth) / 2, y + 18, 0xFFFFFFFF, true);

        gfx.pose().popPose();
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
            case ATLAS -> ChatFormatting.LIGHT_PURPLE;
            case FINALIZE -> ChatFormatting.GOLD;
            case COMPLETE -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
    }

    private static int stageBarColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> 0xFF3B82F6;   // Deep Blue (Sampling)
            case ATLAS -> 0xFFEC4899;      // Pink/Magenta (Atlas)
            case FINALIZE -> 0xFFF59E0B;   // Amber (Writing)
            case COMPLETE -> 0xFF10B981;   // Emerald (Complete)
            default -> 0xFFCCCCCC;
        };
    }
}
