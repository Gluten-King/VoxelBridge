package com.voxelbridge.export;

import com.voxelbridge.thread.ExportThread;
import com.voxelbridge.util.io.IOUtil;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.config.ExportRuntimeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;

/**
 * Unified entry point for selection and export triggers.
 */
public final class ExportControl {
    private static BlockPos pos1;
    private static BlockPos pos2;
    private static volatile ExportThread currentExport;

    private ExportControl() {}

    public static BlockPos getPos1() {
        return pos1;
    }

    public static BlockPos getPos2() {
        return pos2;
    }

    public static void setPos1(BlockPos pos) {
        pos1 = pos != null ? pos.immutable() : null;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void setPos2(BlockPos pos) {
        pos2 = pos != null ? pos.immutable() : null;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void clearSelection() {
        abortExport();
        pos1 = null;
        pos2 = null;
        ExportProgressTracker.clear();
        ProgressNotifier.reset();
    }

    public static ExportResult startExport(Level level) {
        if (pos1 == null || pos2 == null) {
            return new ExportResult(false, "Please set pos1 and pos2 first.");
        }
        if (level == null) {
            return new ExportResult(false, "No world loaded.");
        }

        try {
            ProgressNotifier.reset();
            Path outDir = IOUtil.ensureExportDir();
            Thread exportThread = new ExportThread(level, pos1, pos2, outDir);
            currentExport = (ExportThread) exportThread;
            exportThread.start();
            return new ExportResult(true, "Exporting to glTF...");
        } catch (Exception e) {
            return new ExportResult(false, "Export failed: " + e.getMessage());
        }
    }

    public static void abortExport() {
        ExportProgressTracker.requestAbort();
        ProgressNotifier.reset();
        ExportThread thread = currentExport;
        if (thread != null) {
            thread.interrupt();
        }
        currentExport = null;
    }

    /**
     * Returns the active export for automation/diagnostics, or {@code null} when idle.
     * Callers that need the final result should retain the returned thread and join it.
     */
    public static ExportThread getCurrentExport() {
        return currentExport;
    }

    public static void resetAll() {
        abortExport();
        pos1 = null;
        pos2 = null;
        ExportProgressTracker.clear();
        ExportRuntimeConfig.resetDefaults();
        ProgressNotifier.reset();
    }

    public static void clearActiveExport(ExportThread thread) {
        if (currentExport == thread) {
            currentExport = null;
        }
    }

    public record ExportResult(boolean started, String message) {}
}
