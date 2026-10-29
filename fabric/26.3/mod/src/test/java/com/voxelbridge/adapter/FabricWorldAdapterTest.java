package com.voxelbridge.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FabricWorldAdapterTest {
    @Test
    void convertsInclusiveMinecraftMaximumToExclusiveIterationBound() {
        int minSection = -4;
        int exclusiveMax = FabricWorldAdapter.exclusiveMaxSection(19);

        assertEquals(20, exclusiveMax);
        assertEquals(24, exclusiveMax - minSection);
    }
}
