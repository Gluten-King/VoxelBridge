package com.voxelbridge.export;

import com.voxelbridge.thread.ExportThread;
import com.voxelbridge.util.io.IOUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Path;

/**
 * Unified entry point for selection and export triggers.
 */
public final class ExportControl {
    private static BlockPos pos1;
    private static BlockPos pos2;

    private ExportControl() {}

    public static BlockPos getPos1() {
        return pos1;
    }

    public static BlockPos getPos2() {
        return pos2;
    }

    public static void setPos1(BlockPos pos) {
        pos1 = pos;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void setPos2(BlockPos pos) {
        pos2 = pos;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void clearSelection() {
        pos1 = null;
        pos2 = null;
        ExportProgressTracker.clear();
    }

    public static ExportResult startExport(World level) {
        if (pos1 == null || pos2 == null) {
            return new ExportResult(false, "Please set pos1 and pos2 first.");
        }
        if (level == null) {
            return new ExportResult(false, "No world loaded.");
        }

        try {
            Path outDir = IOUtil.ensureExportDir();
            Thread exportThread = new ExportThread(level, pos1, pos2, outDir);
            exportThread.start();
            return new ExportResult(true, "Exporting to glTF...");
        } catch (Exception e) {
            return new ExportResult(false, "Export failed: " + e.getMessage());
        }
    }

    public record ExportResult(boolean started, String message) {}
}
