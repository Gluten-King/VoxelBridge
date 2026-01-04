package com.voxelbridge.export.exporter.blockentity;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.mixin.RenderStateShardBooleanStateShardAccessor;
import com.voxelbridge.mixin.RenderTypeCompositeRenderTypeAccessor;
import com.voxelbridge.mixin.RenderTypeCompositeStateAccessor;
import com.voxelbridge.mixin.RenderTypeCompositeStateCullAccessor;
import com.voxelbridge.mixin.RenderTypeTextureStateShardAccessor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Resolves texture resource locations from RenderType instances using reflection.
 */
@OnlyIn(Dist.CLIENT)
public final class RenderTypeTextureResolver {

    private static final ConcurrentHashMap<Class<?>, Field> stateFieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> textureStateFieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> textureFieldCache = new ConcurrentHashMap<>();

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

        // Try mixin first
        ResourceLocation fromMixin = resolveFromMixin(renderType);
        if (fromMixin != null) {
            return sanitize(fromMixin);
        }

        // Fallback to reflection
        ResourceLocation fromReflection = resolveFromReflection(renderType);
        return fromReflection != null ? sanitize(fromReflection) : null;
    }

    /**
     * Determines if the RenderType disables back-face culling.
     */
    public static boolean isDoubleSided(RenderType renderType) {
        try {
            if (!(renderType instanceof RenderTypeCompositeRenderTypeAccessor compositeAccessor)) {
                return false;
            }
            RenderType.CompositeState state = compositeAccessor.voxelbridge$state();
            if (state == null) {
                return false;
            }
            Object cullState = ((RenderTypeCompositeStateCullAccessor) (Object) state).voxelbridge$getCullState();
            if (cullState == null) {
                return false;
            }
            if (cullState instanceof RenderStateShardBooleanStateShardAccessor boolShard) {
                return !boolShard.voxelbridge$isEnabled();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static ResourceLocation resolveFromMixin(RenderType renderType) {
        try {
            if (!(renderType instanceof RenderTypeCompositeRenderTypeAccessor compositeAccessor)) {
                return null;
            }
            RenderType.CompositeState state = compositeAccessor.voxelbridge$state();
            if (state == null) {
                return null;
            }
            Object textureState = ((RenderTypeCompositeStateAccessor) (Object) state).voxelbridge$getTextureState();
            if (textureState instanceof RenderType.TextureStateShard shard) {
                Optional<ResourceLocation> texture = ((RenderTypeTextureStateShardAccessor) (Object) shard).voxelbridge$getTexture();
                if (texture != null && texture.isPresent()) {
                    return texture.get();
                }
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.debug(LogModule.TEXTURE_RESOLVE,
                "[RenderTypeTextureResolver] Mixin resolve failed for " + renderType.getClass().getSimpleName() + ": " +
                    t.getClass().getSimpleName() + " - " + t.getMessage());
        }
        return null;
    }

    private static ResourceLocation resolveFromReflection(RenderType renderType) {
        try {
            Class<?> renderTypeClass = renderType.getClass();

            // Find the state field (could be named "state" or similar)
            Field stateField = stateFieldCache.computeIfAbsent(renderTypeClass, clazz -> {
                for (String fieldName : new String[]{"state", "compositeState", "f_110403_"}) {
                    Field f = findField(clazz, fieldName, RenderType.CompositeState.class);
                    if (f != null) return f;
                }
                // Search all fields for CompositeState type
                return findFieldByType(clazz, RenderType.CompositeState.class);
            });

            if (stateField == null) {
                return null;
            }

            Object state = stateField.get(renderType);
            if (state == null) {
                return null;
            }

            // Find textureState field
            Class<?> stateClass = state.getClass();
            Field textureStateField = textureStateFieldCache.computeIfAbsent(stateClass, clazz -> {
                for (String fieldName : new String[]{"textureState", "f_110558_"}) {
                    Field f = findFieldInHierarchy(clazz, fieldName);
                    if (f != null) return f;
                }
                return null;
            });

            if (textureStateField == null) {
                return null;
            }

            Object textureState = textureStateField.get(state);
            if (textureState == null) {
                return null;
            }

            // Find texture field (Optional<ResourceLocation>)
            Class<?> textureStateClass = textureState.getClass();
            Field textureField = textureFieldCache.computeIfAbsent(textureStateClass, clazz -> {
                for (String fieldName : new String[]{"texture", "f_110315_"}) {
                    Field f = findFieldInHierarchy(clazz, fieldName);
                    if (f != null) return f;
                }
                // Search for Optional<ResourceLocation> field
                return findOptionalResourceLocationField(clazz);
            });

            if (textureField == null) {
                return null;
            }

            Object textureOptional = textureField.get(textureState);
            if (textureOptional instanceof Optional<?> opt && opt.isPresent()) {
                Object value = opt.get();
                if (value instanceof ResourceLocation loc) {
                    VoxelBridgeLogger.debug(LogModule.TEXTURE_RESOLVE,
                        "[RenderTypeTextureResolver] Reflection resolved: " + loc);
                    return loc;
                }
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.debug(LogModule.TEXTURE_RESOLVE,
                "[RenderTypeTextureResolver] Reflection resolve failed: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name, Class<?> expectedType) {
        try {
            Field f = clazz.getDeclaredField(name);
            if (expectedType == null || expectedType.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> fieldType) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findOptionalResourceLocationField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (Optional.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static ResourceLocation sanitize(ResourceLocation loc) {
        if (loc == null) return null;
        String namespace = loc.getNamespace();
        String path = loc.getPath();
        if (namespace.contains(":")) {
            namespace = namespace.replace(':', '_');
        }
        if (path.contains(":")) {
            path = path.replace(':', '/');
        }
        if (namespace.equals(loc.getNamespace()) && path.equals(loc.getPath())) {
            return loc;
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
