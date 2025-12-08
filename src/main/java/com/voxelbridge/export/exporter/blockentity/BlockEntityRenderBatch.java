package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.util.ExportLogger;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects BlockEntity render tasks per chunk and executes them in a single
 * main-thread pass to minimize context switching.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityRenderBatch {

    private final List<BlockEntityRenderer.RenderTask> tasks = new ArrayList<>();

    public void enqueue(BlockEntityRenderer.RenderTask task) {
        if (task != null) {
            tasks.add(task);
        }
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Execute all queued tasks on the main thread in one batch.
     */
    public void flush(Minecraft mc) {
        if (tasks.isEmpty()) {
            return;
        }
        mc.executeBlocking(() -> {
            for (BlockEntityRenderer.RenderTask task : tasks) {
                try {
                    task.run();
                } catch (Exception e) {
                    ExportLogger.log("[BlockEntityRenderBatch][ERROR] " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        tasks.clear();
    }

    /**
     * Discard any queued work without rendering.
     */
    public void clear() {
        tasks.clear();
    }
}
