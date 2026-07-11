package com.voxelbridge.thread;

import com.voxelbridge.export.scene.gltf.GltfExportService;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/** Asynchronous glTF export thread with completion notifications. */
public class ExportThread extends Thread {
    private final Level level;
    private final BlockPos pos1, pos2;
    private final Path outDir;
    private volatile Path resultFile;
    private volatile Throwable failure;
    private volatile boolean aborted;

    public ExportThread(Level level, BlockPos pos1, BlockPos pos2, Path outDir) {
        this.level = level;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.outDir = outDir;
        setName("VoxelBridge-Export");
    }

    @Override
    public void run() {
        var mc = ClientAccessHolder.get().getMinecraft();
        try {
            long start = System.currentTimeMillis();

            Path file;
            file = GltfExportService.exportRegion(level, pos1, pos2, outDir);
            resultFile = file;

            long time = System.currentTimeMillis() - start;
            String msg = String.format("[VoxelBridge] Export completed! File: %s (%.2fs)",
                    file.getFileName(), time / 1000.0);

            // In-game notification.
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.displayClientMessage(Component.literal(msg), false);
            });

            VoxelBridgeLogger.info(LogModule.EXPORT, msg);

        } catch (Throwable e) {
            if (Thread.currentThread().isInterrupted()) {
                aborted = true;
                VoxelBridgeLogger.warn(LogModule.EXPORT, "[Export] Export aborted.");
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal("[VoxelBridge] Export aborted."), false);
                    }
                });
                return;
            }
            failure = e;
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            VoxelBridgeLogger.error(LogModule.EXPORT, "[Export][ERROR] Export failed: " + e.getMessage());
            VoxelBridgeLogger.error(LogModule.EXPORT, sw.toString());
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.displayClientMessage(Component.literal("[VoxelBridge] Export failed: " + e.getMessage()), false);
            });
        } finally {
            com.voxelbridge.export.exporter.entity.EntityRenderer.clearSessionCaches();
            com.voxelbridge.export.exporter.blockentity.BlockEntityRenderer.clearSessionCaches();
            com.voxelbridge.export.ExportControl.clearActiveExport(this);
        }
    }

    public Path getResultFile() {
        return resultFile;
    }

    public Throwable getFailure() {
        return failure;
    }

    public boolean wasAborted() {
        return aborted;
    }
}
