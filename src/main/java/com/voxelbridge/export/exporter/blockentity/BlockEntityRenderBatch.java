package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.util.debug.ExportLogger;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects BlockEntity render tasks per chunk and executes them in a single
 * main-thread pass to minimize context switching.
 *
 * Thread-safe for concurrent enqueue from multiple worker threads.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityRenderBatch {

    // Concurrent queue so worker threads can enqueue safely
    private final ConcurrentLinkedQueue<BlockEntityRenderer.RenderTask> tasks = new ConcurrentLinkedQueue<>();

    public void enqueue(BlockEntityRenderer.RenderTask task) {
        if (task != null) {
            tasks.add(task);
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockEntityRenderBatch] Enqueued task, total queued: " + tasks.size());
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
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockEntityRenderBatch] flush() called but queue is empty");
            return;
        }
        int taskCount = tasks.size();
        com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockEntityRenderBatch] flush() called with " + taskCount + " tasks");
        mc.executeBlocking(() -> {
            int executed = 0;
            BlockEntityRenderer.RenderTask task;
            while ((task = tasks.poll()) != null) {
                try {
                    task.run();
                    executed++;
                } catch (Exception e) {
                    com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockEntityRenderBatch][ERROR] " + e.getMessage());
                    e.printStackTrace();
                }
            }
            com.voxelbridge.util.debug.BlockEntityDebugLogger.log("[BlockEntityRenderBatch] Executed " + executed + " tasks");
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
