package com.voxelbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.voxelbridge.platform.client.KeyMappingRegistrar;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Uses InputConstants key codes rather than GLFW: 26.3 moved input to SDL key codes, and the
 * constants are inlined per target at compile time.
 */
public class KeyBindings {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("voxelbridge", "general")
    );

    // Define key bindings.
    public static final KeyMapping KEY_SET_POS1 = new KeyMapping(
            "key.voxelbridge.pos1",
            InputConstants.KEY_NUMPAD7, // Numpad 7
            CATEGORY
    );

    public static final KeyMapping KEY_SET_POS2 = new KeyMapping(
            "key.voxelbridge.pos2",
            InputConstants.KEY_NUMPAD9, // Numpad 9
            CATEGORY
    );

    public static final KeyMapping KEY_EXPORT = new KeyMapping(
            "key.voxelbridge.export",
            InputConstants.KEY_NUMPAD5, // Numpad 5
            CATEGORY
    );

    public static final KeyMapping KEY_CLEAR = new KeyMapping(
            "key.voxelbridge.clear",
            InputConstants.KEY_NUMPAD0, // Numpad 0
            CATEGORY
    );

    public static final KeyMapping KEY_CONFIG = new KeyMapping(
            "key.voxelbridge.config",
            InputConstants.KEY_NUMPAD8, // Numpad 8
            CATEGORY
    );

    public static void register(KeyMappingRegistrar registrar) {
        registrar.register(KEY_SET_POS1);
        registrar.register(KEY_SET_POS2);
        registrar.register(KEY_EXPORT);
        registrar.register(KEY_CLEAR);
        registrar.register(KEY_CONFIG);
    }
}
