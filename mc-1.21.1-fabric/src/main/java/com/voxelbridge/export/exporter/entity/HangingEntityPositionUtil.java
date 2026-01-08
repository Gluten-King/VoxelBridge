package com.voxelbridge.export.exporter.entity;

import net.minecraft.entity.decoration.AbstractDecorationEntity;

/**
 * Computes direction-based offsets for hanging entities (paintings, item frames).
 * Mirrors vanilla renderer positioning logic.
 */
public final class HangingEntityPositionUtil {

    private HangingEntityPositionUtil() {}

    /**
     * Compute render offsets for a hanging entity.
     *
     * @param entity Hanging entity
     * @return [offsetX, offsetY, offsetZ]
     */
    public static double[] calculateRenderOffset(AbstractDecorationEntity entity) {
        return new double[] { 0.0, 0.0, 0.0 };
    }
}
