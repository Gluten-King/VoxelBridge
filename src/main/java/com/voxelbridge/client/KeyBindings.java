package com.voxelbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxelbridge.VoxelBridge;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

// Registers every key binding on the client mod bus.
@EventBusSubscriber(modid = VoxelBridge.MODID, bus = EventBusSubscriber.Bus.MOD)
public class KeyBindings {

    // Define key bindings.
    public static final KeyMapping KEY_SET_POS1 = new KeyMapping(
            "key.voxelbridge.pos1",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_7, // Numpad 7
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_SET_POS2 = new KeyMapping(
            "key.voxelbridge.pos2",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_9, // Numpad 9
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_EXPORT = new KeyMapping(
            "key.voxelbridge.export",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_5, // Numpad 5
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_CLEAR = new KeyMapping(
            "key.voxelbridge.clear",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_0, // Numpad 0
            "key.categories.voxelbridge"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KEY_SET_POS1);
        event.register(KEY_SET_POS2);
        event.register(KEY_EXPORT);
        event.register(KEY_CLEAR);
    }
}
