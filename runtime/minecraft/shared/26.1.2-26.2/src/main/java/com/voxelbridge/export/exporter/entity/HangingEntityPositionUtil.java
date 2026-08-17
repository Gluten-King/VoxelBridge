package com.voxelbridge.export.exporter.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Utility for calculating render offsets for hanging entities.
 */
public final class HangingEntityPositionUtil {

    private HangingEntityPositionUtil() {}

    /**
     * Returns render offset for a hanging entity based on its direction and size.
     */
    public static double[] calculateRenderOffset(HangingEntity entity) {
        Direction direction = entity.getDirection();
        Vec3 offset = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        double halfWidth = entity.getBbWidth() / 2.0;
        double halfHeight = entity.getBbHeight() / 2.0;

        double x = offset.x * 0.46875D + halfWidth;
        double y = offset.y + halfHeight;
        double z = offset.z * 0.46875D + halfWidth;

        return new double[] {x, y, z};
    }
}
