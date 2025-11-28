package com.voxelbridge;

import com.voxelbridge.command.VoxelBridgeCommands;
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
    }
}
