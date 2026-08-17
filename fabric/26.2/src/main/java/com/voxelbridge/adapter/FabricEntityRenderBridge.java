package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.platform.render.capture.BufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Fabric entity render bridge using the MC 26.x extractRenderState()+submit() pipeline.
 */
public final class FabricEntityRenderBridge implements EntityRenderBridge {

    @Override
    public boolean shouldApplyHangingOffset() {
        return false;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object createRenderState(net.minecraft.client.renderer.entity.EntityRenderer renderer,
            Entity entity,
            float yaw,
            float partial) {
        return renderer.createRenderState(entity, partial);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Vec3 getRenderOffset(net.minecraft.client.renderer.entity.EntityRenderer renderer,
            Entity entity,
            float partial,
            Object renderState) {
        if (renderState instanceof EntityRenderState state) {
            return renderer.getRenderOffset(state);
        }
        return Vec3.ZERO;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void render(net.minecraft.client.renderer.entity.EntityRenderer renderer,
            Object renderState,
            Entity entity,
            float yaw,
            float partial,
            PoseStack poseStack,
            BufferSource buffer,
            int packedLight) {
        EntityRenderState state = renderState instanceof EntityRenderState s
            ? s
            : renderer.createRenderState(entity, partial);
        renderer.extractRenderState(entity, state, partial);
        var collector = new CapturingSubmitNodeCollector(buffer, entity);
        CameraRenderState cameraState = new CameraRenderState();
        Minecraft.getInstance().gameRenderer.mainCamera().extractRenderState(cameraState, partial);
        renderer.submit(state, poseStack, collector, cameraState);
    }
}
