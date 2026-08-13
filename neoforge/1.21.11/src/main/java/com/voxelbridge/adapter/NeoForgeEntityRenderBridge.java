package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.platform.render.capture.ImmediateSubmitNodeCollector;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class NeoForgeEntityRenderBridge implements EntityRenderBridge {
    @Override
    public boolean shouldApplyHangingOffset() {
        return false;
    }

    @Override
    public Object createRenderState(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                                    Entity entity, float yaw, float partial) {
        return renderer.createRenderState(entity, partial);
    }

    @Override
    public Vec3 getRenderOffset(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                                Entity entity, float partial, Object renderState) {
        return renderState instanceof EntityRenderState state ? renderer.getRenderOffset(state) : Vec3.ZERO;
    }

    @Override
    public void render(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                       Object renderState,
                       Entity entity,
                       float yaw,
                       float partial,
                       PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight) {
        EntityRenderState state = renderState instanceof EntityRenderState s
                ? s : renderer.createRenderState(entity, partial);
        renderer.submit(state, poseStack, new ImmediateSubmitNodeCollector(buffer),
                ImmediateSubmitNodeCollector.cameraState(entity.position()));
    }
}
