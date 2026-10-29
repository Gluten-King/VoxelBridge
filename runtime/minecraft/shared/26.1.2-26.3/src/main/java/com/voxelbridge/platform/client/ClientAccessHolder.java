package com.voxelbridge.platform.client;

import java.util.Objects;

/**
 * Central holder for client access implementation.
 */
public final class ClientAccessHolder {
    private static volatile ClientAccess instance;

    private ClientAccessHolder() {}

    public static ClientAccess get() {
        ClientAccess current = instance;
        if (current == null) {
            current = new MinecraftClientAccess();
            instance = current;
        }
        return current;
    }

    public static void set(ClientAccess access) {
        instance = Objects.requireNonNull(access, "access");
    }
}
