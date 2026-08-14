package com.voxelbridge.adapter;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric implementation of PlatformRenderHelper using public APIs.
 */
public class FabricPlatformRenderHelper implements PlatformRenderHelper {

    @Override
    public ResourceLocation getRenderTypeTexture(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        try {
            String name = renderType.toString();
            if (name.contains("RenderType[")) {
                int texIdx = name.indexOf("texture=");
                if (texIdx >= 0) {
                    int start = texIdx + 8;
                    int end = name.indexOf(",", start);
                    if (end < 0) {
                        end = name.indexOf("]", start);
                    }
                    if (end > start) {
                        String texStr = normalizeTextureToken(name.substring(start, end).trim());
                        if (texStr != null) {
                            return ResourceLocation.parse(texStr);
                        }
                    }
                }
            }
            String optional = normalizeTextureToken(parseOptionalTexture(name));
            return optional != null ? ResourceLocation.parse(optional) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean isRenderTypeDoubleSided(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        try {
            String name = renderType.toString();
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("cull")) {
                return lower.contains("no_cull") || lower.contains("nocull");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String parseOptionalTexture(String renderTypeString) {
        if (renderTypeString == null) {
            return null;
        }
        String marker = "texture[Optional[";
        int start = renderTypeString.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = renderTypeString.indexOf("]", valueStart);
        if (valueEnd <= valueStart) {
            return null;
        }
        String tex = renderTypeString.substring(valueStart, valueEnd).trim();
        if (tex.isEmpty() || "empty".equals(tex)) {
            return null;
        }
        return tex;
    }

    private static String normalizeTextureToken(String token) {
        if (token == null) {
            return null;
        }
        String tex = token.trim();
        if (tex.isEmpty() || "empty".equalsIgnoreCase(tex)) {
            return null;
        }
        if (tex.startsWith("Optional[")) {
            int start = "Optional[".length();
            int end = tex.lastIndexOf(']');
            if (end > start) {
                tex = tex.substring(start, end).trim();
            }
        }
        if (tex.startsWith("texture[Optional[")) {
            int start = "texture[Optional[".length();
            int end = tex.lastIndexOf(']');
            if (end > start) {
                tex = tex.substring(start, end).trim();
            }
        }
        if (tex.endsWith("]")) {
            int bracket = tex.indexOf('[');
            if (bracket >= 0 && bracket < tex.length() - 1) {
                tex = tex.substring(bracket + 1, tex.length() - 1).trim();
            }
        }
        return tex.isEmpty() ? null : tex;
    }

    @Override
    public boolean isOnRenderThread() {
        return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
    }

    @Override
    public void recordRenderCall(Runnable task) {
        if (task == null) {
            return;
        }
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            task.run();
        } else {
            com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(task);
        }
    }

    @Override
    public net.minecraft.world.phys.Vec3 getBlockOffset(net.minecraft.world.level.block.state.BlockState state,
                                                       net.minecraft.world.level.Level level,
                                                       net.minecraft.core.BlockPos pos) {
        if (state == null || pos == null) {
            return net.minecraft.world.phys.Vec3.ZERO;
        }
        return state.getOffset(pos);
    }

    @Override
    public boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state,
                                 net.minecraft.world.level.Level level,
                                 net.minecraft.core.BlockPos pos) {
        if (state == null) {
            return false;
        }
        return state.isSolidRender();
    }

    @Override
    public void awaitTextureDump() {
        var fence = com.mojang.blaze3d.systems.RenderSystem.getDevice()
            .createCommandEncoder().createFence();
        try {
            if (!fence.awaitCompletion(5_000_000_000L)) {
                throw new IllegalStateException("Timed out waiting for GPU texture readback");
            }
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks();
        } finally {
            fence.close();
        }
    }

    @Override
    public com.mojang.blaze3d.vertex.PoseStack getGuiPose(net.minecraft.client.gui.GuiGraphics gfx) {
        if (gfx == null) {
            return null;
        }
        org.joml.Matrix3x2fStack stack = gfx.pose();
        return stack != null ? new GuiPoseStack(stack) : null;
    }

    @Override
    public void pushPose(com.mojang.blaze3d.vertex.PoseStack pose) {
        if (pose != null) {
            pose.pushPose();
        }
    }

    @Override
    public void popPose(com.mojang.blaze3d.vertex.PoseStack pose) {
        if (pose != null) {
            pose.popPose();
        }
    }

    @Override
    public void translatePose(com.mojang.blaze3d.vertex.PoseStack pose, float x, float y, float z) {
        if (pose != null) {
            pose.translate(x, y, z);
        }
    }

    @Override
    public int drawString(net.minecraft.client.gui.GuiGraphics gfx,
                          net.minecraft.client.gui.Font font,
                          String text,
                          int x,
                          int y,
                          int color,
                          boolean shadow) {
        if (gfx == null || font == null || text == null) {
            return 0;
        }
        gfx.drawString(font, text, x, y, color, shadow);
        return font.width(text);
    }

    @Override
    public int drawString(net.minecraft.client.gui.GuiGraphics gfx,
                          net.minecraft.client.gui.Font font,
                          net.minecraft.network.chat.Component text,
                          int x,
                          int y,
                          int color,
                          boolean shadow) {
        if (gfx == null || font == null || text == null) {
            return 0;
        }
        gfx.drawString(font, text, x, y, color, shadow);
        return font.width(text);
    }

    private static final class GuiPoseStack extends com.mojang.blaze3d.vertex.PoseStack {
        private final org.joml.Matrix3x2fStack stack;

        private GuiPoseStack(org.joml.Matrix3x2fStack stack) {
            this.stack = stack;
        }

        @Override
        public void pushPose() {
            if (stack != null) {
                stack.pushMatrix();
            }
        }

        @Override
        public void popPose() {
            if (stack != null) {
                stack.popMatrix();
            }
        }

        @Override
        public void translate(float x, float y, float z) {
            if (stack != null) {
                stack.translate(x, y);
            }
        }

        @Override
        public void translate(double x, double y, double z) {
            if (stack != null) {
                stack.translate((float) x, (float) y);
            }
        }

        @Override
        public void translate(net.minecraft.world.phys.Vec3 vec) {
            if (stack != null && vec != null) {
                stack.translate((float) vec.x, (float) vec.y);
            }
        }
    }
}
