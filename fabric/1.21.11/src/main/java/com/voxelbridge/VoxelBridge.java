/*
 * VoxelBridge Fabric mod entry point.
 */
package com.voxelbridge;

import com.voxelbridge.adapter.*;
import com.voxelbridge.platform.FabricPlatformBootstrap;
import com.voxelbridge.platform.PlatformBootstrap;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.client.MinecraftClientAccess;
import net.fabricmc.api.ClientModInitializer;

public class VoxelBridge implements ClientModInitializer {
    public static final String MODID = ModConstants.MOD_ID;

    @Override
    public void onInitializeClient() {
        ClientAccessHolder.set(new MinecraftClientAccess());
        Adapters.init(
                new FabricWorldAdapter(),
                new FabricRenderAdapter(),
                new FabricEntityRenderBridge(),
                new FabricBlockEntityRenderBridge(),
                new FabricSelectionRenderBridge(),
                new FabricFluidSpriteResolver(),
                new NoopModHandlerBridge(),
                new FabricPlatformRenderHelper(),
                new FabricPlatformTextureHelper(),
                new FabricPlatformModelHelper());
        PlatformBootstrap platform = new FabricPlatformBootstrap();
        platform.init();
    }
}
