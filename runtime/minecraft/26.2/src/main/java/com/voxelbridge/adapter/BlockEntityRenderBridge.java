package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.platform.render.capture.BufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Version bridge for block entity renderer signatures.
 * MC 26.2: MultiBufferSource replaced by local BufferSource.
 */
public interface BlockEntityRenderBridge {
    void render(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                BlockEntity blockEntity,
                float partial,
                PoseStack poseStack,
                BufferSource buffer,
                int packedLight,
                int packedOverlay,
                Vec3 cameraPos);
}
