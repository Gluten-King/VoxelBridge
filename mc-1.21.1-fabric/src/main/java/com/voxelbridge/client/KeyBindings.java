package com.voxelbridge.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.function.Function;

/**
 * Fabric keybinding definitions.
 */
public final class KeyBindings {

    public static final KeyBinding KEY_SET_POS1 = new KeyBinding(
            "key.voxelbridge.pos1",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_7,
            "key.categories.voxelbridge"
    );

    public static final KeyBinding KEY_SET_POS2 = new KeyBinding(
            "key.voxelbridge.pos2",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_9,
            "key.categories.voxelbridge"
    );

    public static final KeyBinding KEY_EXPORT = new KeyBinding(
            "key.voxelbridge.export",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_5,
            "key.categories.voxelbridge"
    );

    public static final KeyBinding KEY_CLEAR = new KeyBinding(
            "key.voxelbridge.clear",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_0,
            "key.categories.voxelbridge"
    );

    private KeyBindings() {}

    /**
     * Registers all keybindings with the given registrar (default: KeyBindingHelper::registerKeyBinding).
     */
    public static void register(Function<KeyBinding, KeyBinding> registrar) {
        registrar.apply(KEY_SET_POS1);
        registrar.apply(KEY_SET_POS2);
        registrar.apply(KEY_EXPORT);
        registrar.apply(KEY_CLEAR);
    }
}
