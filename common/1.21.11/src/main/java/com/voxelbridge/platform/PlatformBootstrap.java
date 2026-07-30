package com.voxelbridge.platform;

/**
 * Platform-specific bootstrap for event registration.
 * Pure interface, decoupled from specific mod loader types.
 */
public interface PlatformBootstrap {
    void init();
}