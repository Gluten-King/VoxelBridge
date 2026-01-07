package com.voxelbridge.thread;

import com.voxelbridge.export.scene.gltf.GltfExportService;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

/** Asynchronous glTF export thread with completion notifications. */
public class ExportThread extends Thread {
    private final World level;
    private final BlockPos pos1, pos2;
    private final Path outDir;

    public ExportThread(World level, BlockPos pos1, BlockPos pos2, Path outDir) {
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

            Path file = GltfExportService.exportRegion(level, pos1, pos2, outDir);

            long time = System.currentTimeMillis() - start;
            String msg = String.format("[VoxelBridge] Export completed! File: %s (%.2fs)",
                    file.getFileName(), time / 1000.0);

            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(msg), false);
                }
            });

            VoxelBridgeLogger.info(LogModule.EXPORT, msg);
        } catch (Throwable e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            VoxelBridgeLogger.error(LogModule.EXPORT, "[Export][ERROR] Export failed: " + e.getMessage());
            VoxelBridgeLogger.error(LogModule.EXPORT, sw.toString());
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("[VoxelBridge] Export failed: " + e.getMessage()), false);
                }
            });
        }
    }
}
