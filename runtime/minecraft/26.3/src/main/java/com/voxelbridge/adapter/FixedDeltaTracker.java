package com.voxelbridge.adapter;

import net.minecraft.client.DeltaTracker;

/**
 * DeltaTracker frozen at one partial tick. MC 26.3 camera state extraction takes a DeltaTracker
 * instead of a float; export renders use a fixed partial tick so captures stay deterministic.
 */
public record FixedDeltaTracker(float partialTick) implements DeltaTracker {

    public static DeltaTracker of(float partialTick) {
        return partialTick == 0.0f ? DeltaTracker.ZERO : new FixedDeltaTracker(partialTick);
    }

    @Override
    public float getGameTimeDeltaTicks() {
        return 0.0f;
    }

    @Override
    public float getGameTimeDeltaPartialTick(boolean runsNormally) {
        return partialTick;
    }

    @Override
    public float getRealtimeDeltaTicks() {
        return 0.0f;
    }
}
