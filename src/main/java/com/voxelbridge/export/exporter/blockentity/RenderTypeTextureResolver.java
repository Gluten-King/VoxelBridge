package com.voxelbridge.export.exporter.blockentity;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;


/**
 * Resolves texture resource locations from RenderType instances using reflection.
 */
@OnlyIn(Dist.CLIENT)
public final class RenderTypeTextureResolver {

    private RenderTypeTextureResolver() {
    }

    /**
     * Attempts to extract the texture ResourceLocation from a RenderType.
     *
     * @param renderType the render type
     * @return the texture location, or null if it cannot be determined
     */
    public static ResourceLocation resolve(RenderType renderType) {
        if (renderType == null) {
            return null;
        }

        ResourceLocation fromState = extractFromState(renderType);
        if (fromState != null) {
            return fromState;
        }

        try {
            // Try to get the texture from the RenderType
            // This uses reflection since RenderType internals are not part of the public API
            return extractTextureViaReflection(renderType);
        } catch (Exception e) {
            // Reflection failed, return null
            return null;
        }
    }

    /**
     * Determines if the RenderType disables back-face culling.
     */
    public static boolean isDoubleSided(RenderType renderType) {
        try {
            RenderType.CompositeState state = compositeState(renderType);
            if (state == null) {
                return false;
            }
            Field cullField = RenderType.CompositeState.class.getDeclaredField("cullState");
            cullField.setAccessible(true);
            Object cullState = cullField.get(state);
            if (cullState == null) {
                return false;
            }
            Class<?> booleanShard = cullState.getClass().getSuperclass(); // BooleanStateShard
            Field enabled = booleanShard.getDeclaredField("enabled");
            enabled.setAccessible(true);
            boolean cullEnabled = enabled.getBoolean(cullState);
            return !cullEnabled;
        } catch (Exception e) {
            return false;
        }
    }

    private static ResourceLocation extractFromState(RenderType renderType) {
        try {
            RenderType.CompositeState state = compositeState(renderType);
            if (state == null) {
                return null;
            }

            Field textureField = RenderType.CompositeState.class.getDeclaredField("textureState");
            textureField.setAccessible(true);
            Object textureState = textureField.get(state);
            if (textureState == null) {
                return null;
            }

            Method cutoutMethod = textureState.getClass().getDeclaredMethod("cutoutTexture");
            cutoutMethod.setAccessible(true);
            Object result = cutoutMethod.invoke(textureState);
            if (result instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof ResourceLocation loc) {
                return loc;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static RenderType.CompositeState compositeState(RenderType renderType) {
        try {
            // Use reflection to access CompositeRenderType and state() method
            Class<?> compositeRenderTypeClass = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            if (!compositeRenderTypeClass.isInstance(renderType)) {
                return null;
            }

            Method stateMethod = compositeRenderTypeClass.getDeclaredMethod("state");
            stateMethod.setAccessible(true);
            Object state = stateMethod.invoke(renderType);
            if (state instanceof RenderType.CompositeState compositeState) {
                return compositeState;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ResourceLocation extractTextureViaReflection(RenderType renderType) {
        try {
            // Try to access toString() and parse it (fallback method)
            String name = renderType.toString();
            if (name.contains("RenderType[")) {
                // Parse texture from toString output if possible
                // Format is usually like "RenderType[name, texture=minecraft:textures/..., ...]"
                int texIdx = name.indexOf("texture=");
                if (texIdx >= 0) {
                    int start = texIdx + 8;
                    int end = name.indexOf(",", start);
                    if (end < 0) end = name.indexOf("]", start);
                    if (end > start) {
                        String texStr = name.substring(start, end).trim();
                        return ResourceLocation.parse(texStr);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
