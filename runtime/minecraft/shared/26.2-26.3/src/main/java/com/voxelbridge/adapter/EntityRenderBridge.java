package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.export.exporter.entity.EntityTextureResolver;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.platform.render.capture.BufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Version bridge for entity render-state pipelines.
 * MC 26.2: MultiBufferSource replaced by local BufferSource.
 */
public interface EntityRenderBridge {
    default boolean shouldApplyHangingOffset() {
        return true;
    }

    default Vec3 getHangingOffsetBase(HangingEntity entity) {
        return null;
    }

    default TextureResolver<Entity> getTextureResolver() {
        return EntityTextureResolver.INSTANCE;
    }

    Object createRenderState(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                             Entity entity,
                             float yaw,
                             float partial);

    Vec3 getRenderOffset(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                         Entity entity,
                         float partial,
                         Object renderState);

    void render(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                Object renderState,
                Entity entity,
                float yaw,
                float partial,
                PoseStack poseStack,
                BufferSource buffer,
                int packedLight);
}
