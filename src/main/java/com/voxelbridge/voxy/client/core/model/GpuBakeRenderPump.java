package com.voxelbridge.voxy.client.core.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.VoxelBridge;
import com.voxelbridge.voxy.client.core.rendering.util.UploadStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = VoxelBridge.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GpuBakeRenderPump {
    private static final long DEFAULT_BUDGET_NANOS = 50_000_000L;
    private static final Set<ModelBakerySubsystem> ACTIVE = ConcurrentHashMap.newKeySet();

    private GpuBakeRenderPump() {}

    public static void register(ModelBakerySubsystem subsystem) {
        if (subsystem != null) {
            ACTIVE.add(subsystem);
        }
    }

    public static void unregister(ModelBakerySubsystem subsystem) {
        if (subsystem != null) {
            ACTIVE.remove(subsystem);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ACTIVE.isEmpty() && !GpuBakeDebugProbe.isActive()) {
            return;
        }
        if (RenderSystem.isOnRenderThread()) {
            tickAll();
        } else {
            RenderSystem.recordRenderCall(GpuBakeRenderPump::tickAll);
        }
    }

    private static void tickAll() {
        for (ModelBakerySubsystem subsystem : ACTIVE) {
            subsystem.tick(DEFAULT_BUDGET_NANOS);
        }
        GpuBakeDebugProbe.tick();
        UploadStream.INSTANCE.tick();
    }
}
