package com.voxelbridge.export.exporter.entity;

import net.minecraft.world.entity.decoration.HangingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Computes direction-based offsets for hanging entities (paintings, item frames).
 * Mirrors vanilla renderer positioning logic.
 */
@OnlyIn(Dist.CLIENT)
public final class HangingEntityPositionUtil {

    private HangingEntityPositionUtil() {}

    /**
     * Compute render offsets for a hanging entity.
     * Hanging entities sit on block surfaces and need a slight outward offset.
     *
     * @param entity Hanging entity
     * @return [offsetX, offsetY, offsetZ]
     */
    public static double[] calculateRenderOffset(HangingEntity entity) {
        // Use vanilla offsets directly to match in-game visuals.
        return new double[] { 0.0, 0.0, 0.0 };
    }
}
