package com.voxelbridge.util.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Utility for querying the block under the player crosshair (Fabric).
 */
public final class RayCastUtil {
    private RayCastUtil() {}

    public static BlockPos getLookingAt(MinecraftClient mc, double distance) {
        if (mc == null || mc.player == null || mc.world == null) return null;
        Vec3d start = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d end = start.add(look.x * distance, look.y * distance, look.z * distance);
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (result.getType() == HitResult.Type.BLOCK) {
            return result.getBlockPos();
        }
        return null;
    }
}
