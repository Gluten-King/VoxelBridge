package com.voxelbridge.adapter;

import com.voxelbridge.mixin.CompositeRenderTypeAccessor;
import com.voxelbridge.mixin.CompositeStateAccessor;
import com.voxelbridge.mixin.EmptyTextureStateShardAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * NeoForge implementation of PlatformRenderHelper.
 */
public class NeoForgePlatformRenderHelper implements PlatformRenderHelper {
    private static java.lang.reflect.Method drawStringTextWithShadow;
    private static java.lang.reflect.Method drawStringTextNoShadow;
    private static java.lang.reflect.Method drawStringComponentWithShadow;
    private static java.lang.reflect.Method drawStringComponentNoShadow;
    private static boolean drawStringResolved;

    // RenderType helpers
    @Override
    public ResourceLocation getRenderTypeTexture(RenderType renderType) {
        if (renderType == null)
            return null;

        ResourceLocation fromState = extractFromState(renderType);
        if (fromState != null) {
            return sanitize(fromState);
        }

        ResourceLocation fromString = extractFromString(renderType);
        if (fromString != null) {
            return sanitize(fromString);
        }

        return null;
    }

    @Override
    public boolean isRenderTypeDoubleSided(RenderType renderType) {
        RenderType.CompositeState state = compositeState(renderType);
        if (state == null) {
            return false;
        }
        var states = ((CompositeStateAccessor) (Object) state).voxelbridge$getStates();
        if (states == null) {
            return false;
        }
        for (RenderStateShard shard : states) {
            if (shard == null) {
                continue;
            }
            String name = shard.toString();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("cull")) {
                return lower.contains("no_cull") || lower.contains("nocull");
            }
        }
        return false;
    }

    // --- Private helpers moved from RenderTypeTextureResolver ---

    private static ResourceLocation extractFromState(RenderType renderType) {
        RenderType.CompositeState state = compositeState(renderType);
        if (state == null) {
            return null;
        }
        RenderStateShard.EmptyTextureStateShard textureState =
                ((CompositeStateAccessor) (Object) state).voxelbridge$getTextureState();
        if (textureState == null) {
            return null;
        }
        Optional<ResourceLocation> result =
                ((EmptyTextureStateShardAccessor) (Object) textureState).voxelbridge$cutoutTexture();
        return result != null ? result.orElse(null) : null;
    }

    private static ResourceLocation extractFromString(RenderType renderType) {
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
                        String texStr = name.substring(start, end).trim();
                        return ResourceLocation.parse(texStr);
                    }
                }
            }
            String optional = parseOptionalTexture(name);
            if (optional != null) {
                return ResourceLocation.parse(optional);
            }
        } catch (Exception ignored) {
        }
        return null;
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

    private static RenderType.CompositeState compositeState(RenderType renderType) {
        if (!(renderType instanceof CompositeRenderTypeAccessor composite)) {
            return null;
        }
        return composite.voxelbridge$getState();
    }

    private static ResourceLocation sanitize(ResourceLocation loc) {
        if (loc == null)
            return null;
        String namespace = loc.getNamespace();
        String path = loc.getPath();
        if (namespace.contains(":"))
            namespace = namespace.replace(':', '_');
        if (path.contains(":"))
            path = path.replace(':', '/');
        if (namespace.equals(loc.getNamespace()) && path.equals(loc.getPath()))
            return loc;
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    @Override
    public boolean isOnRenderThread() {
        return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
    }

    @Override
    public void recordRenderCall(Runnable task) {
        // 1.21.8: RenderSystem API changed, try direct execution if on render thread
        if (task == null) {
            return;
        }
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            task.run();
        } else {
            // If not on render thread, we need to schedule it somehow
            // Try using the recordRenderCall with method reference syntax
            try {
                // Attempt reflection as last resort for compatibility
                java.lang.reflect.Method method = com.mojang.blaze3d.systems.RenderSystem.class
                    .getMethod("recordRenderCall", Runnable.class);
                method.invoke(null, task);
            } catch (Exception e) {
                // If all else fails, just run it (may cause issues but won't crash)
                task.run();
            }
        }
    }

    @Override
    public net.minecraft.world.phys.Vec3 getBlockOffset(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (state == null || pos == null)
            return net.minecraft.world.phys.Vec3.ZERO;
        // 1.21.8: getOffset only takes BlockPos
        return state.getOffset(pos);
    }

    @Override
    public boolean isSolidRender(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (state == null)
            return false;
        // 1.21.8: isSolidRender takes no parameters
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
        if (gfx == null)
            return null;
        // 1.21.8: GuiGraphics.pose() returns Matrix3x2fStack, we need to return null
        // as PoseStack is not directly accessible from GuiGraphics in 1.21.8
        return null;
    }

    @Override
    public void pushPose(com.mojang.blaze3d.vertex.PoseStack pose) {
        if (pose != null)
            pose.pushPose();
    }

    @Override
    public void popPose(com.mojang.blaze3d.vertex.PoseStack pose) {
        if (pose != null)
            pose.popPose();
    }

    @Override
    public void translatePose(com.mojang.blaze3d.vertex.PoseStack pose, float x, float y, float z) {
        if (pose != null)
            pose.translate(x, y, z);
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
        resolveDrawStringMethods();
        try {
            if (shadow && drawStringTextWithShadow != null) {
                Object result = drawStringTextWithShadow.invoke(gfx, font, text, x, y, color, true);
                return (result instanceof Integer i) ? i : 0;
            }
            if (drawStringTextNoShadow != null) {
                Object result = drawStringTextNoShadow.invoke(gfx, font, text, x, y, color);
                return (result instanceof Integer i) ? i : 0;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            gfx.drawString(font, text, x, y, color);
        } catch (Throwable ignored) {
            // Ignore: fallback may not exist.
        }
        return 0;
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
        resolveDrawStringMethods();
        try {
            if (shadow && drawStringComponentWithShadow != null) {
                Object result = drawStringComponentWithShadow.invoke(gfx, font, text, x, y, color, true);
                return (result instanceof Integer i) ? i : 0;
            }
            if (drawStringComponentNoShadow != null) {
                Object result = drawStringComponentNoShadow.invoke(gfx, font, text, x, y, color);
                return (result instanceof Integer i) ? i : 0;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            gfx.drawString(font, text, x, y, color);
        } catch (Throwable ignored) {
            // Ignore: fallback may not exist.
        }
        return 0;
    }

    private static void resolveDrawStringMethods() {
        if (drawStringResolved) {
            return;
        }
        drawStringResolved = true;
        try {
            drawStringTextWithShadow = net.minecraft.client.gui.GuiGraphics.class.getMethod(
                    "drawString", net.minecraft.client.gui.Font.class, String.class,
                    int.class, int.class, int.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            drawStringTextWithShadow = null;
        }
        try {
            drawStringTextNoShadow = net.minecraft.client.gui.GuiGraphics.class.getMethod(
                    "drawString", net.minecraft.client.gui.Font.class, String.class,
                    int.class, int.class, int.class);
        } catch (NoSuchMethodException ignored) {
            drawStringTextNoShadow = null;
        }
        try {
            drawStringComponentWithShadow = net.minecraft.client.gui.GuiGraphics.class.getMethod(
                    "drawString", net.minecraft.client.gui.Font.class, net.minecraft.network.chat.Component.class,
                    int.class, int.class, int.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            drawStringComponentWithShadow = null;
        }
        try {
            drawStringComponentNoShadow = net.minecraft.client.gui.GuiGraphics.class.getMethod(
                    "drawString", net.minecraft.client.gui.Font.class, net.minecraft.network.chat.Component.class,
                    int.class, int.class, int.class);
        } catch (NoSuchMethodException ignored) {
            drawStringComponentNoShadow = null;
        }
    }
}
