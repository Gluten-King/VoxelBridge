package com.voxelbridge.voxy.client.core.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.export.GpuBakeDebugService;
import com.voxelbridge.voxy.client.core.gl.GlDebug;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicInteger;

public final class GpuBakeDebugProbe {
    private static final int DEFAULT_SIZE = 16;
    private static final AtomicInteger REMAINING_FRAMES = new AtomicInteger(0);
    private static volatile BlockState targetState = Blocks.GRANITE.defaultBlockState();
    private static GpuBakeDebugService service;

    private GpuBakeDebugProbe() {}

    public static boolean isActive() {
        return REMAINING_FRAMES.get() > 0;
    }

    public static void arm(BlockState state, int frames) {
        if (state == null || frames <= 0) {
            return;
        }
        targetState = state;
        REMAINING_FRAMES.set(frames);
        GlDebug.setForceDebugGroups(true);
    }

    public static void tick() {
        if (REMAINING_FRAMES.get() <= 0) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(GpuBakeDebugProbe::tick);
            return;
        }
        if (service == null) {
            service = new GpuBakeDebugService(DEFAULT_SIZE);
        }
        BlockState state = targetState != null ? targetState : Blocks.GRANITE.defaultBlockState();
        GlDebug.pushGroup("VoxelBridge Bake Probe");
        try {
            service.bake(state);
        } finally {
            GlDebug.popGroup();
        }
        if (REMAINING_FRAMES.decrementAndGet() <= 0) {
            shutdown();
        }
    }

    private static void shutdown() {
        if (service != null) {
            service.close();
            service = null;
        }
        GlDebug.setForceDebugGroups(false);
    }
}
