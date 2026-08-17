package com.voxelbridge.adapter;

import com.voxelbridge.mixin.RenderSetupAccessor;
import com.voxelbridge.mixin.RenderTypeAccessor;
import com.voxelbridge.mixin.TextureBindingAccessor;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Fabric implementation of PlatformRenderHelper using public APIs.
 */
public class FabricPlatformRenderHelper implements PlatformRenderHelper {

    @Override
    public Identifier getRenderTypeTexture(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).voxelbridge$getState();
        Object primary = ((RenderSetupAccessor) (Object) setup).voxelbridge$getTextures().get("Sampler0");
        return primary != null
                ? ((TextureBindingAccessor) primary).voxelbridge$getLocation()
                : null;
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

    @Override
    public com.voxelbridge.core.ir.RenderLayer getRenderTypeLayer(RenderType renderType) {
        if (renderType == null) {
            return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
        }
        try {
            String name = renderType.toString();
            if (name == null) {
                return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            // Terrain / true translucent passes first.
            if (lower.contains("tripwire") || lower.contains("clouds")
                    || (lower.contains("translucent") && !lower.contains("entity"))) {
                return com.voxelbridge.core.ir.RenderLayer.TRANSLUCENT;
            }
            // Water uses the translucent terrain pass; match by name too.
            if (lower.contains("water") && !lower.contains("entity")) {
                return com.voxelbridge.core.ir.RenderLayer.TRANSLUCENT;
            }
            // entity_translucent / armor translucent: MC still depth-tests these.
            // Export as CUTOUT (glTF MASK) so DCC viewers write depth and hide
            // internal model faces. True soft entity translucency is rare.
            if (lower.contains("entity") && lower.contains("translucent")) {
                return com.voxelbridge.core.ir.RenderLayer.CUTOUT;
            }
            if (lower.contains("cutout") || lower.contains("crumbling")
                    || lower.contains("cross") || lower.contains("armor_cutout")) {
                return com.voxelbridge.core.ir.RenderLayer.CUTOUT;
            }
            if (lower.contains("solid") || lower.contains("entity") || lower.contains("item")) {
                return com.voxelbridge.core.ir.RenderLayer.SOLID;
            }
        } catch (Exception ignored) {
        }
        return com.voxelbridge.core.ir.RenderLayer.UNKNOWN;
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
    public com.mojang.blaze3d.vertex.PoseStack getGuiPose(net.minecraft.client.gui.GuiGraphicsExtractor gfx) {
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
    public int drawString(net.minecraft.client.gui.GuiGraphicsExtractor gfx,
                          net.minecraft.client.gui.Font font,
                          String text,
                          int x,
                          int y,
                          int color,
                          boolean shadow) {
        if (gfx == null || font == null || text == null) {
            return 0;
        }
        gfx.text(font, text, x, y, color, shadow);
        return font.width(text);
    }

    @Override
    public int drawString(net.minecraft.client.gui.GuiGraphicsExtractor gfx,
                          net.minecraft.client.gui.Font font,
                          net.minecraft.network.chat.Component text,
                          int x,
                          int y,
                          int color,
                          boolean shadow) {
        if (gfx == null || font == null || text == null) {
            return 0;
        }
        gfx.text(font, text.getVisualOrderText(), x, y, color, shadow);
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
