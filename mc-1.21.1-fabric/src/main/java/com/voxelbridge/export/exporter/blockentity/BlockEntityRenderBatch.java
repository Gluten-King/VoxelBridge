package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects BlockEntity render tasks per chunk and executes them in a single
 * main-thread pass to minimize context switching.
 *
 * Thread-safe for concurrent enqueue from multiple worker threads.
 */
public final class BlockEntityRenderBatch {

    private final ConcurrentLinkedQueue<BlockEntityRenderer.RenderTask> tasks = new ConcurrentLinkedQueue<>();

    public void enqueue(BlockEntityRenderer.RenderTask task) {
        if (task != null) {
            tasks.add(task);
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderBatch] Enqueued task, total queued: " + tasks.size());
        }
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * Execute all queued tasks on the main thread in one batch.
     */
    public void flush(MinecraftClient mc) {
        if (tasks.isEmpty()) {
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderBatch] flush() called but queue is empty");
            return;
        }
        int taskCount = tasks.size();
        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderBatch] flush() called with " + taskCount + " tasks");
        mc.submitAndJoin(() -> {
            int executed = 0;
            BlockEntityRenderer.RenderTask task;
            while ((task = tasks.poll()) != null) {
                try {
                    task.run();
                    executed++;
                } catch (Exception e) {
                    VoxelBridgeLogger.error(LogModule.BLOCKENTITY, "[BlockEntityRenderBatch][ERROR] " + e.getMessage());
                    e.printStackTrace();
                }
            }
            VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, "[BlockEntityRenderBatch] Executed " + executed + " tasks");
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
