package com.voxelbridge.platform;

import net.minecraft.client.Minecraft;

/**
 * Platform-specific config screen bridge.
 */
public final class ConfigScreenBridge {
    private static ConfigScreenOpener opener;

    private ConfigScreenBridge() {
    }

    public static void setOpener(ConfigScreenOpener configOpener) {
        opener = configOpener;
    }

    public static void openConfigScreen(Minecraft mc) {
        if (opener != null && mc != null) {
            opener.open(mc);
        }
    }

    @FunctionalInterface
    public interface ConfigScreenOpener {
        void open(Minecraft mc);
    }
}
