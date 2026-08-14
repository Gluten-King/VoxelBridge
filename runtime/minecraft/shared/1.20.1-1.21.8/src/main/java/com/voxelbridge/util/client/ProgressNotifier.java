package com.voxelbridge.util.client;

import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.compat.GuiPoseCompat;
import com.voxelbridge.export.ExportProgressTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;

/**
 * Client-side progress notifications: action bar text + HUD progress bar.
 */
public final class ProgressNotifier {

    private static ExportProgressTracker.Progress lastProgress;
    private static long lastProgressNanos = 0L;
    private static float smoothedPercent = 0f;
    private static long lastRenderNanos = 0L;

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

    public static void reset() {
        lastProgress = null;
        lastProgressNanos = 0L;
        smoothedPercent = 0f;
        lastRenderNanos = 0L;
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
        if (mc == null) {
            return;
        }
        ExportProgressTracker.Progress current = ExportProgressTracker.progress();
        if (lastProgress == null) {
            if (current.stage() != ExportProgressTracker.Stage.IDLE
                && current.stage() != ExportProgressTracker.Stage.COMPLETE) {
                lastProgress = current;
                lastProgressNanos = System.nanoTime();
                smoothedPercent = 0f; // Start new process from 0
            } else {
                return;
            }
        } else {
            boolean stageChanged = current.stage() != lastProgress.stage();
            boolean detailChanged = !Objects.equals(current.stageDetail(), lastProgress.stageDetail());
            boolean percentChanged = !Objects.equals(current.phasePercent(), lastProgress.phasePercent());
            
            if (stageChanged) {
                // When stage changes (e.g. Sampling -> Atlas), strictly reset visual progress to 0
                // This ensures the user sees a "0 to 100" run for every stage, preventing "backward" movement
                smoothedPercent = 0f;
                lastProgressNanos = System.nanoTime();
                lastRenderNanos = System.nanoTime();
            } else if (detailChanged || percentChanged) {
                 lastProgressNanos = System.nanoTime();
                 lastRenderNanos = System.nanoTime();
            }
            
            // Always update lastProgress
            if (current.total() > 0 || current.stage() == ExportProgressTracker.Stage.COMPLETE) {
                lastProgress = current;
            }
        }
        float fadeAlpha = 1.0f;
        if (lastProgress.stage() == ExportProgressTracker.Stage.COMPLETE) {
            long elapsedNs = System.nanoTime() - lastProgressNanos;
            long holdNs = 3_000_000_000L;
            long fadeNs = 1_000_000_000L;
            if (elapsedNs > holdNs) {
                long fadeElapsed = elapsedNs - holdNs;
                if (fadeElapsed >= fadeNs) {
                    lastProgress = null;
                    return;
                }
                fadeAlpha = 1.0f - (fadeElapsed / (float) fadeNs);
            }
        }

        int screenW = mc.getWindow().getGuiScaledWidth();
        // Move to TOP of screen (Boss Bar position)
        int barWidth = 182;
        int barHeight = 6;
        int x = (screenW - barWidth) / 2;
        int y = 12; // Top offset

        float targetPct = Math.max(0f, Math.min(1f, lastProgress.displayPercent() / 100f));
        long now = System.nanoTime();
        if (lastRenderNanos == 0L) {
            lastRenderNanos = now;
        }
        float dt = (now - lastRenderNanos) / 1_000_000_000f;
        lastRenderNanos = now;
        
        // Lower speed means the bar lags behind the real progress more, creating a smoother "fill up" animation 
        // even if the real task finishes instantly.
        // Was 10f, now 2.0f for "fast" fill to ensure visibility.
        float speed = targetPct >= smoothedPercent ? 2.0f : 10.0f; // Fast drop (reset), slow rise
        
        float alpha = 1f - (float) Math.exp(-speed * Math.min(dt, 0.25f));
        smoothedPercent = smoothedPercent + (targetPct - smoothedPercent) * alpha;

        // Visual clamp to ensure it doesn't look empty or overfilled
        float dispPct = Math.max(0f, Math.min(1f, smoothedPercent));
        int filled = Math.round(barWidth * dispPct);

        // Raise Z-level to render above everything
        GuiPoseCompat.push(gfx);
        GuiPoseCompat.translate(gfx, 0, 0, 1000.0f);

        // Outline (Black border)
        gfx.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, applyAlpha(0xFF000000, fadeAlpha));
        // Background
        gfx.fill(x, y, x + barWidth, y + barHeight, applyAlpha(0xFF444444, fadeAlpha));
        // Progress
        gfx.fill(x, y, x + filled, y + barHeight, applyAlpha(stageBarColor(lastProgress.stage()), fadeAlpha));

        // Line 1: Title
        String title = String.format("[%s] %s %.1f%%",
                ExportProgressTracker.getFormatLabel(),
                stageLabel(lastProgress.stage(), lastProgress.stageDetail()),
                lastProgress.displayPercent());
        int titleWidth = mc.font.width(title);
        int titleColor = applyAlpha(stageBarColor(lastProgress.stage()), fadeAlpha);
        Adapters.getPlatformRenderHelper().drawString(
                gfx, mc.font, title, (screenW - titleWidth) / 2, y + 8, titleColor, true);

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
        int detailColor = applyAlpha(0xFFFFFFFF, fadeAlpha);
        Adapters.getPlatformRenderHelper().drawString(
                gfx, mc.font, details, (screenW - detailWidth) / 2, y + 18, detailColor, true);

        GuiPoseCompat.pop(gfx);
    }

    private static String stageBase(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> "Sampling";
            case ATLAS -> "Atlas";
            case PBR_DECODE -> "PBR Decode";
            case FINALIZE -> "Finalize";
            case COMPLETE -> "Complete";
            default -> "Preparing";
        };
    }

    private static ChatFormatting stageTextColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> ChatFormatting.BLUE;
            case ATLAS -> ChatFormatting.LIGHT_PURPLE;
            case PBR_DECODE -> ChatFormatting.LIGHT_PURPLE;
            case FINALIZE -> ChatFormatting.GOLD;
            case COMPLETE -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
    }

    private static int stageBarColor(ExportProgressTracker.Stage stage) {
        return switch (stage) {
            case SAMPLING -> 0xFF17E6E6;   // Cyan (#17E6E6)
            case ATLAS -> 0xFFE617A1;      // Pink (#E617A1)
            case PBR_DECODE -> 0xFFE617E6; // Magenta (#E617E6)
            case FINALIZE -> 0xFFE6E617;   // Yellow (#E6E617)
            case COMPLETE -> 0xFF17E617;   // Green (#17E617)
            default -> 0xFFCCCCCC;
        };
    }

    private static int applyAlpha(int argb, float alpha) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        int a = (argb >>> 24) & 0xFF;
        int na = Math.round(a * alpha);
        return (na << 24) | (argb & 0x00FFFFFF);
    }
}
