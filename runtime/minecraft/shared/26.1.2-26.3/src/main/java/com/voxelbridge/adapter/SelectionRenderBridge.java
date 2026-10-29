package com.voxelbridge.adapter;

/**
 * Version bridge for selection overlay rendering.
 */
public interface SelectionRenderBridge {
    /**
     * Register selection renderer to the appropriate event on the platform hook.
     */
    void register(Object platformHook);
}
