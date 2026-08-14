package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.export.exporter.entity.EntityTextureResolver;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Version bridge for entity render-state pipelines.
 */
public interface EntityRenderBridge {
    /**
     * Whether to apply extra hanging-entity render offset compensation.
     */
    default boolean shouldApplyHangingOffset() {
        return true;
    }

    /**
     * Provides the base position for hanging-entity offsets.
     * Returning null uses the entity's current position.
     */
    default Vec3 getHangingOffsetBase(HangingEntity entity) {
        return null;
    }

    /**
     * Provides the entity texture resolver for the current platform.
     */
    default TextureResolver<Entity> getTextureResolver() {
        return EntityTextureResolver.INSTANCE;
    }
    /**
     * Creates a render-state object for the given entity.
     */
    Object createRenderState(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                             Entity entity,
                             float yaw,
                             float partial);

    /**
     * Resolves the render offset for the entity/state pair.
     */
    Vec3 getRenderOffset(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                         Entity entity,
                         float partial,
                         Object renderState);

    /**
     * Renders the entity using the provided state (or legacy params).
     */
    void render(net.minecraft.client.renderer.entity.EntityRenderer renderer,
                Object renderState,
                Entity entity,
                float yaw,
                float partial,
                PoseStack poseStack,
                MultiBufferSource buffer,
                int packedLight);
}
