package com.voxelbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxelbridge.platform.client.KeyMappingRegistrar;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {

    // Define key bindings.
    public static final KeyMapping KEY_SET_POS1 = new KeyMapping(
            "key.voxelbridge.pos1",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_7, // Numpad 7
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_SET_POS2 = new KeyMapping(
            "key.voxelbridge.pos2",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_9, // Numpad 9
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_EXPORT = new KeyMapping(
            "key.voxelbridge.export",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_5, // Numpad 5
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_CLEAR = new KeyMapping(
            "key.voxelbridge.clear",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_0, // Numpad 0
            "key.categories.voxelbridge"
    );

    public static final KeyMapping KEY_CONFIG = new KeyMapping(
            "key.voxelbridge.config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_8, // Numpad 8
            "key.categories.voxelbridge"
    );

    public static void register(KeyMappingRegistrar registrar) {
        registrar.register(KEY_SET_POS1);
        registrar.register(KEY_SET_POS2);
        registrar.register(KEY_EXPORT);
        registrar.register(KEY_CLEAR);
        registrar.register(KEY_CONFIG);
    }
}
