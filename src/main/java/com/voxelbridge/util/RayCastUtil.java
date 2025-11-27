package com.voxelbridge.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Utility for querying the block under the player crosshair. */
@OnlyIn(Dist.CLIENT)
public final class RayCastUtil {
    private RayCastUtil() {}

    public static BlockPos getLookingAt(Minecraft mc, double distance) {
        if (mc.player == null || mc.level == null) return null;
        var result = mc.player.pick(distance, 0.0F, false);
        if (result.getType() == HitResult.Type.BLOCK)
            return ((BlockHitResult) result).getBlockPos();
        return null;
    }
}
