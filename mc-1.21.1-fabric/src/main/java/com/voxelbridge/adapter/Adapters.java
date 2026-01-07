package com.voxelbridge.adapter;

public final class Adapters {
    private static WorldAdapter worldAdapter;
    private static RenderAdapter renderAdapter;

    private Adapters() {}

    public static void init(WorldAdapter worldAdapterImpl, RenderAdapter renderAdapterImpl) {
        worldAdapter = worldAdapterImpl;
        renderAdapter = renderAdapterImpl;
    }

    public static WorldAdapter getWorld() {
        if (worldAdapter == null) throw new IllegalStateException("WorldAdapter not initialized!");
        return worldAdapter;
    }

    public static RenderAdapter getRender() {
        if (renderAdapter == null) throw new IllegalStateException("RenderAdapter not initialized!");
        return renderAdapter;
    }
}
