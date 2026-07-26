package com.voxelbridge.adapter;

/**
 * Interface for accessing platform-specific rendering internals.
 * Should be implemented by platform modules to avoid reflection in common code.
 */
public interface PlatformRenderHelper {

    // RenderType helpers
    net.minecraft.resources.Identifier getRenderTypeTexture(net.minecraft.client.renderer.rendertype.RenderType renderType);
    boolean isRenderTypeDoubleSided(net.minecraft.client.renderer.rendertype.RenderType renderType);

    /**
     * Best-effort map from a RenderType to IR RenderLayer for alphaMode.
     * Default UNKNOWN when the platform cannot classify the type.
     */
    default com.voxelbridge.core.ir.RenderLayer getRenderTypeLayer(net.minecraft.client.renderer.rendertype.RenderType renderType) {
        return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
    }

    // RenderSystem helpers
    boolean isOnRenderThread();
    void recordRenderCall(Runnable task);

    // BlockState helpers
    net.minecraft.world.phys.Vec3 getBlockOffset(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos);
    boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos);

    // GUI Pose helpers
    com.mojang.blaze3d.vertex.PoseStack getGuiPose(net.minecraft.client.gui.GuiGraphicsExtractor gfx);
    void pushPose(com.mojang.blaze3d.vertex.PoseStack pose);
    void popPose(com.mojang.blaze3d.vertex.PoseStack pose);
    void translatePose(com.mojang.blaze3d.vertex.PoseStack pose, float x, float y, float z);

    // GUI draw helpers
    int drawString(net.minecraft.client.gui.GuiGraphicsExtractor gfx,
                   net.minecraft.client.gui.Font font,
                   java.lang.String text,
                   int x,
                   int y,
                   int color,
                   boolean shadow);

    int drawString(net.minecraft.client.gui.GuiGraphicsExtractor gfx,
                   net.minecraft.client.gui.Font font,
                   net.minecraft.network.chat.Component text,
                   int x,
                   int y,
                   int color,
                   boolean shadow);
}
