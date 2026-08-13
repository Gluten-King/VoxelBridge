package com.voxelbridge.adapter;

import com.voxelbridge.mixin.RenderSetupAccessor;
import com.voxelbridge.mixin.RenderTypeAccessor;
import com.voxelbridge.mixin.TextureBindingAccessor;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric implementation of PlatformRenderHelper using public APIs.
 */
public class FabricPlatformRenderHelper implements PlatformRenderHelper {

    private static final Set<Identifier> PROBED_STRUCTURED_TEXTURES = ConcurrentHashMap.newKeySet();

    @Override
    public Identifier getRenderTypeTexture(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        Identifier structured = getStructuredTexture(renderType);
        if (structured != null) {
            if (VoxelBridgeLogger.isProbeEnabled() && PROBED_STRUCTURED_TEXTURES.add(structured)) {
                VoxelBridgeLogger.probe("render-type-texture source=structured texture=" + structured);
            }
            return structured;
        }
        if (Boolean.getBoolean("voxelbridge.renderType.requireStructuredTexture")) {
            return null;
        }
        try {
            String name = renderType.toString();
            String binding = parseTextureBindingLocation(name);
            if (binding != null) {
                return Identifier.parse(binding);
            }
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
                            return Identifier.parse(texStr);
                        }
                    }
                }
            }
            String optional = normalizeTextureToken(parseOptionalTexture(name));
            return optional != null ? Identifier.parse(optional) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reads the actual 1.21.11 render-state texture binding. Accessor names are
     * remapped by Loom, unlike the text emitted by record {@code toString()}.
     */
    private static Identifier getStructuredTexture(RenderType renderType) {
        try {
            RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).voxelbridge$getState();
            if (setup == null) {
                return null;
            }
            Map<String, ?> bindings = ((RenderSetupAccessor) (Object) setup).voxelbridge$getTextureBindings();
            if (bindings == null || bindings.isEmpty()) {
                return null;
            }

            Object primary = bindings.get("Sampler0");
            Identifier location = getBindingLocation(primary);
            if (location != null) {
                return location;
            }
            for (Object binding : bindings.values()) {
                location = getBindingLocation(binding);
                if (location != null) {
                    return location;
                }
            }
        } catch (ClassCastException | LinkageError ignored) {
            // Preserve the string fallback for unusual third-party RenderTypes.
        }
        return null;
    }

    private static Identifier getBindingLocation(Object binding) {
        if (binding == null) {
            return null;
        }
        try {
            return ((TextureBindingAccessor) binding).voxelbridge$getLocation();
        } catch (ClassCastException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Minecraft 1.21.11 exposes sampler textures as
     * {@code TextureBinding[location=minecraft:..., sampler=...]}. Keep this
     * parser local to the 1.21.11 adapter so older RenderType formats remain
     * untouched.
     */
    private static String parseTextureBindingLocation(String renderTypeString) {
        if (renderTypeString == null) {
            return null;
        }
        String marker = "TextureBinding[location=";
        int markerStart = renderTypeString.indexOf(marker);
        if (markerStart < 0) {
            return null;
        }
        int valueStart = markerStart + marker.length();
        int valueEnd = renderTypeString.indexOf(',', valueStart);
        if (valueEnd < 0) {
            valueEnd = renderTypeString.indexOf(']', valueStart);
        }
        if (valueEnd <= valueStart) {
            return null;
        }
        String texture = renderTypeString.substring(valueStart, valueEnd).trim();
        return texture.isEmpty() ? null : texture;
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
