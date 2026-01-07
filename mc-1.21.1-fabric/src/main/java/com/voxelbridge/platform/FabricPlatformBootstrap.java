package com.voxelbridge.platform;

import com.voxelbridge.client.KeyBindings;
import com.voxelbridge.client.KeyInputHandler;
import com.voxelbridge.client.SelectionRenderer;
import com.voxelbridge.client.HudOverlayRenderer;
import com.voxelbridge.command.VoxelBridgeCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Fabric event wiring for client-only hooks.
 */
public final class FabricPlatformBootstrap implements PlatformBootstrap {

    @Override
    public void init() {
        // Keybindings
        KeyBindings.register(key -> KeyBindingHelper.registerKeyBinding(key));

        // Client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> KeyInputHandler.onClientTick(client));

        // Client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                VoxelBridgeCommands.registerFabric(dispatcher));

        // World render (selection boxes / chunk status)
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                SelectionRenderer.onRenderLevel(context));

        // HUD overlay (optional)
        // HudRenderCallback.EVENT.register((drawContext, tickDelta) ->
        //         HudOverlayRenderer.onRenderGui(drawContext, tickDelta, MinecraftClient.getInstance()));
    }
}
