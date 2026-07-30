package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.platform.render.capture.ImmediateSubmitNodeCollector;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric 1.21.1 block entity render bridge (legacy signature).
 */
public final class FabricBlockEntityRenderBridge implements BlockEntityRenderBridge {

    @Override
    public void render(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
            BlockEntity blockEntity,
            float partial,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay,
            Vec3 cameraPos) {
        BlockEntityRenderState state = (BlockEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(blockEntity, state, partial, cameraPos, null);
        renderer.submit(
            state,
            poseStack,
            new ImmediateSubmitNodeCollector(buffer),
            ImmediateSubmitNodeCollector.cameraState(cameraPos)
        );
    }
}
