package com.voxelbridge.platform;

import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.client.HudOverlayRenderer;
import com.voxelbridge.client.KeyBindings;
import com.voxelbridge.client.KeyInputHandler;
import com.voxelbridge.config.ExportConfigStore;
import com.voxelbridge.platform.FabricCommands;
import com.voxelbridge.platform.ConfigScreenBridge;
import com.voxelbridge.export.ExportControl;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Fabric event registration for client-only hooks.
 */
public final class FabricPlatformBootstrap implements PlatformBootstrap {

    @Override
    public void init() {
        // Register key bindings
        KeyBindings.register(KeyMappingHelper::registerKeyMapping);

        // Register client commands using Fabric's command API
        ClientCommandRegistrationCallback.EVENT
                .register((dispatcher, registryAccess) -> FabricCommands.register(dispatcher));

        // Register client tick handler
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeyInputHandler.onClientTick();
            FabricConfigScreen.onClientTick(client);
        });

        // Register selection render (already done in adapter init via register())
        Adapters.getSelectionRender().register(null);

        // Register HUD overlay (attach after vanilla MISC_OVERLAYS with a unique element id)
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("voxelbridge", "progress_overlay"),
                (gfx, deltaTracker) -> HudOverlayRenderer.render(gfx));

        // Load persistent config once client is ready
        ExportConfigStore.init();

        ConfigScreenBridge.setOpener(mc -> FabricConfigScreen.requestOpen(mc.screen));

        // Only clear selection on world disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ExportControl.clearSelection());
    }
}
