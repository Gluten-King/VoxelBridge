package com.voxelbridge.fabric.command;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

public final class VoxelBridgeFabricCommands {
    private VoxelBridgeFabricCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("voxelbridge")
                .then(ClientCommandManager.literal("ping")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Text.literal("Pong"));
                        return 1;
                    })
                )
            );
        });
    }
}
