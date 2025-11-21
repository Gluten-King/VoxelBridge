package com.voxelbridge.thread;

import com.voxelbridge.export.scene.gltf.GltfExportService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Asynchronous glTF export thread with completion notifications. */
@OnlyIn(Dist.CLIENT)
public class ExportThread extends Thread {
    private final Level level;
    private final BlockPos pos1, pos2;
    private final Path outDir;

    public ExportThread(Level level, BlockPos pos1, BlockPos pos2, Path outDir) {
        this.level = level;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.outDir = outDir;
        setName("MineExporter-glTF");
    }

    @Override
    public void run() {
        Minecraft mc = Minecraft.getInstance();
        try {
            long start = System.currentTimeMillis();

            Path file = GltfExportService.exportRegion(level, pos1, pos2, outDir);

            long time = System.currentTimeMillis() - start;
            String msg = String.format("[VoxelBridge] Export completed! File: %s (%.2fs)",
                    file.getFileName(), time / 1000.0);

            // In-game notification.
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.displayClientMessage(Component.literal(msg), false);
            });

            // Console output.
            System.out.println(msg);

        } catch (Throwable e) {
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            com.voxelbridge.util.ExportLogger.log("[GLTF][ERROR] Export failed: " + e.getMessage());
            com.voxelbridge.util.ExportLogger.log(sw.toString());
            mc.execute(() -> {
                if (mc.player != null)
                    mc.player.displayClientMessage(Component.literal("[VoxelBridge] Export failed: " + e.getMessage()), false);
            });
        }
    }
}
