package com.voxelbridge.util.client;

import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-side progress notifications: action bar text + HUD progress bar.
 */
public final class ProgressNotifier {

    private static ExportProgressTracker.Progress lastProgress;
    private static long lastProgressNanos = 0L;

    private ProgressNotifier() {}

    public static void show(MinecraftClient mc, double percent, int processed, int total) {
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
            mc.player.sendMessage(Text.literal(text), true);
        });
    }

    public static void showDetailed(MinecraftClient mc, ExportProgressTracker.Progress progress) {
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

    public static void renderOverlay(MinecraftClient mc, DrawContext gfx) {
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

        int screenW = mc.getWindow().getScaledWidth();
        // Move to TOP of screen (Boss Bar position)
        int barWidth = 182;
        int barHeight = 6;
        int x = (screenW - barWidth) / 2;
        int y = 12; // Top offset

        float dispPct = Math.max(0f, Math.min(1f, lastProgress.displayPercent() / 100f));
        int filled = Math.round(barWidth * dispPct);

        // Raise Z-level to render above everything
        gfx.getMatrices().push();
        gfx.getMatrices().translate(0, 0, 1000.0f);

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
        int titleWidth = mc.textRenderer.getWidth(title);
        int titleColor = stageBarColor(lastProgress.stage());
        gfx.drawText(mc.textRenderer, title, (screenW - titleWidth) / 2, y + 8, titleColor, true);

        // Line 2: Colorful Details
        MutableText details = Text.empty();
        
        if (lastProgress.stage() == ExportProgressTracker.Stage.SAMPLING) {
            details.append(Text.literal("Chunks: ").formatted(Formatting.AQUA))
                   .append(Text.literal(String.format("%d/%d", lastProgress.done() + lastProgress.failed(), lastProgress.total()))
                           .formatted(Formatting.WHITE));
        }

        String etaStr = eta(lastProgress);
        if (!etaStr.isEmpty()) {
            if (!details.getString().isEmpty()) details.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY));
            details.append(Text.literal("ETA: ").formatted(Formatting.GOLD))
                   .append(Text.literal(etaStr.replace("ETA: ", "")).formatted(Formatting.YELLOW));
        }

        if (!details.getString().isEmpty()) details.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY));
        
        Formatting memColor = isHighMemory() ? Formatting.RED : Formatting.GREEN;
        details.append(Text.literal("Mem: ").formatted(Formatting.LIGHT_PURPLE))
               .append(Text.literal(memoryStats()).formatted(memColor));
        
        int detailWidth = mc.textRenderer.getWidth(details);
        gfx.drawText(mc.textRenderer, details, (screenW - detailWidth) / 2, y + 18, 0xFFFFFFFF, true);

        gfx.getMatrices().pop();
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

    private static Formatting stageTextColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> Formatting.BLUE;
            case ATLAS -> Formatting.LIGHT_PURPLE;
            case FINALIZE -> Formatting.GOLD;
            case COMPLETE -> Formatting.GREEN;
            default -> Formatting.WHITE;
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
