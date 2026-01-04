package com.voxelbridge.util.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityRendererAccessor {
    private static final Map<Class<?>, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private EntityRendererAccessor() {}

    public static <T extends Entity, S extends EntityRenderState> ResourceLocation getTextureLocation(
        EntityRenderer<T, S> renderer,
        S state,
        T entity
    ) {
        if (renderer == null) return null;

        Class<?> clazz = renderer.getClass();
        Method method = METHOD_CACHE.computeIfAbsent(clazz, EntityRendererAccessor::findTextureMethod);

        if (method == null) return null;

        try {
            method.setAccessible(true);
            if (method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                if (EntityRenderState.class.isAssignableFrom(paramType)) {
                    return (ResourceLocation) method.invoke(renderer, state);
                } else if (Entity.class.isAssignableFrom(paramType)) {
                    return (ResourceLocation) method.invoke(renderer, entity);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Method findTextureMethod(Class<?> clazz) {
        try {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("getTextureLocation")
                    && m.getParameterCount() == 1
                    && ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                    return m;
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals("getTextureLocation")
                    && m.getParameterCount() == 1
                    && ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                    return m;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("getTexture")
                    && m.getParameterCount() == 1
                    && ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                    return m;
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals("getTexture")
                    && m.getParameterCount() == 1
                    && ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                    return m;
                }
            }
        } catch (Exception ignored) {
        }

        for (Method m : clazz.getMethods()) {
            if (ResourceLocation.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 1) {
                Class<?> param = m.getParameterTypes()[0];
                if (EntityRenderState.class.isAssignableFrom(param)) return m;
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (ResourceLocation.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 1) {
                Class<?> param = m.getParameterTypes()[0];
                if (EntityRenderState.class.isAssignableFrom(param)) return m;
            }
        }

        for (Method m : clazz.getMethods()) {
            if (ResourceLocation.class.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 1) {
                Class<?> param = m.getParameterTypes()[0];
                if (Entity.class.isAssignableFrom(param)) return m;
            }
        }
        return null;
    }
}
