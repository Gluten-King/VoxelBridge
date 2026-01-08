package com.voxelbridge.fabric.command;

import com.voxelbridge.command.VoxelBridgeCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class VoxelBridgeFabricCommands {
    private VoxelBridgeFabricCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            VoxelBridgeCommands.registerFabric(dispatcher);
        });
    }
}
