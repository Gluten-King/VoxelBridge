package com.voxelbridge.export.exporter.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 计算悬挂实体（画、展示框）基于朝向的位置偏移。
 * 复制Minecraft原版渲染器的位置逻辑。
 */
@OnlyIn(Dist.CLIENT)
public final class HangingEntityPositionUtil {

    private HangingEntityPositionUtil() {}

    /**
     * 计算悬挂实体的渲染偏移量。
     * 悬挂实体附着在方块表面，需要略微向外偏移以正确渲染。
     *
     * @param entity 悬挂实体
     * @return [offsetX, offsetY, offsetZ] 数组
     */
    public static double[] calculateRenderOffset(HangingEntity entity) {
        // 使用原版渲染器的矩阵即可，不再额外偏移，避免与游戏内视觉不一致
        return new double[] { 0.0, 0.0, 0.0 };
    }
}
