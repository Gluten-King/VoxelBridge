package com.voxelbridge.adapter;

/**
 * Interface for accessing platform-specific rendering internals.
 * Should be implemented by platform modules to avoid reflection in common code.
 */
public interface PlatformRenderHelper {

    // RenderType helpers
    net.minecraft.resources.ResourceLocation getRenderTypeTexture(net.minecraft.client.renderer.RenderType renderType);
    boolean isRenderTypeDoubleSided(net.minecraft.client.renderer.RenderType renderType);

    // RenderSystem helpers
    boolean isOnRenderThread();
    void recordRenderCall(Runnable task);

    /**
     * Waits until a GPU-backed texture dump has finished writing its output.
     * Older render backends write synchronously and keep the default no-op.
     */
    default void awaitTextureDump() {
    }

    // BlockState helpers
    net.minecraft.world.phys.Vec3 getBlockOffset(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos);
    boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos);

    // GUI Pose helpers
    com.mojang.blaze3d.vertex.PoseStack getGuiPose(net.minecraft.client.gui.GuiGraphics gfx);
    void pushPose(com.mojang.blaze3d.vertex.PoseStack pose);
    void popPose(com.mojang.blaze3d.vertex.PoseStack pose);
    void translatePose(com.mojang.blaze3d.vertex.PoseStack pose, float x, float y, float z);

    // GUI draw helpers
    int drawString(net.minecraft.client.gui.GuiGraphics gfx,
                   net.minecraft.client.gui.Font font,
                   java.lang.String text,
                   int x,
                   int y,
                   int color,
                   boolean shadow);

    int drawString(net.minecraft.client.gui.GuiGraphics gfx,
                   net.minecraft.client.gui.Font font,
                   net.minecraft.network.chat.Component text,
                   int x,
                   int y,
                   int color,
                   boolean shadow);
}
