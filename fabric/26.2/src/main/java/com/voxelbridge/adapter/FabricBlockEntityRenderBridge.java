package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.platform.render.capture.BufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric block entity render bridge using the MC 26.x extractRenderState()+submit() pipeline.
 */
public final class FabricBlockEntityRenderBridge implements BlockEntityRenderBridge {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void render(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
            BlockEntity blockEntity,
            float partial,
            PoseStack poseStack,
            BufferSource buffer,
            int packedLight,
            int packedOverlay,
            Vec3 cameraPos) {
        var state = renderer.createRenderState();
        renderer.extractRenderState(blockEntity, state, partial, cameraPos, null);
        var collector = new CapturingSubmitNodeCollector(buffer);
        CameraRenderState cameraState = new CameraRenderState();
        Minecraft.getInstance().gameRenderer.mainCamera().extractRenderState(cameraState, partial);
        renderer.submit(state, poseStack, collector, cameraState);
    }
}
