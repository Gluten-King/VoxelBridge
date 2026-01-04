/*
 * VoxelBridge mod entry point.
 */
package com.voxelbridge;
import com.voxelbridge.command.VoxelBridgeCommands;
import com.voxelbridge.client.KeyBindings;
import com.voxelbridge.client.KeyInputHandler;
import com.voxelbridge.client.SelectionRenderer;
import com.voxelbridge.client.HudOverlayRenderer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;


@Mod(VoxelBridge.MODID)
public class VoxelBridge {
    public static final String MODID = "voxelbridge";

    public VoxelBridge(IEventBus modBus, ModContainer container, Dist dist) {
        NeoForge.EVENT_BUS.addListener(VoxelBridgeCommands::register);
        modBus.addListener(KeyBindings::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(KeyInputHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(SelectionRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HudOverlayRenderer::onRenderGui);
    }
}
