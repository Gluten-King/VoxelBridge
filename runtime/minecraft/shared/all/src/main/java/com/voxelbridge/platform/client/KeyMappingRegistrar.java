package com.voxelbridge.platform.client;

import net.minecraft.client.KeyMapping;

@FunctionalInterface
public interface KeyMappingRegistrar {
    void register(KeyMapping mapping);
}
