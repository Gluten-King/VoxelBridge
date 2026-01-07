package com.voxelbridge.fabric;

import com.voxelbridge.fabric.command.VoxelBridgeFabricCommands;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.client.MinecraftClientAccess;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.adapter.FabricWorldAdapter;
import com.voxelbridge.adapter.FabricRenderAdapter;
import com.voxelbridge.platform.FabricPlatformBootstrap;
import net.fabricmc.api.ClientModInitializer;

public final class VoxelBridgeFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Set up shared client access and platform-specific adapters.
        ClientAccessHolder.set(new MinecraftClientAccess());
        Adapters.init(new FabricWorldAdapter(), new FabricRenderAdapter());

        // Register Fabric-side events (keys, commands, rendering hooks).
        new FabricPlatformBootstrap().init();

        // Keep legacy simple command registration (ping) for sanity check.
        VoxelBridgeFabricCommands.register();
    }
}
