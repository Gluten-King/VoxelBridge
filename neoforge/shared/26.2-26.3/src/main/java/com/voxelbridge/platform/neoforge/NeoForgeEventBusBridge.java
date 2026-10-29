package com.voxelbridge.platform.neoforge;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/**
 * Best-effort bridge for resolving and registering NeoForge event buses across versions.
 */
public final class NeoForgeEventBusBridge {
    private static volatile Object cachedGameBus;

    private NeoForgeEventBusBridge() {}

    public static Object resolveGameBus(Object modBus) {
        Object cached = cachedGameBus;
        if (cached != null) {
            return cached;
        }
        Object global = resolveGlobalBus();
        cachedGameBus = (global != null) ? global : modBus;
        return cachedGameBus;
    }

    public static <T> void addListener(Object bus, Consumer<T> listener) {
        if (bus == null || listener == null) {
            return;
        }
        if (invokeAddListener(bus, listener)) {
            return;
        }
        if (invokeRegister(bus, listener)) {
            return;
        }
        VoxelBridgeLogger.warn(LogModule.EXPORT,
            "[NeoForgeEventBusBridge] Failed to register listener on " + bus.getClass().getName());
    }

    private static Object resolveGlobalBus() {
        Object fromNeoForge = findBusFromClass("net.neoforged.neoforge.common.NeoForge");
        if (fromNeoForge != null) {
            return fromNeoForge;
        }
        return null;
    }

    private static Object findBusFromClass(String className) {
        try {
            Class<?> type = Class.forName(className);
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && isEventBus(value)) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isEventBus(Object value) {
        String name = value.getClass().getName();
        return name.contains("EventBus") || name.contains("IEventBus");
    }

    private static boolean invokeAddListener(Object bus, Object listener) {
        Method method = findSingleArgMethod(bus.getClass(), "addListener", listener);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(bus, listener);
            return true;
        } catch (Throwable t) {
            VoxelBridgeLogger.warn(LogModule.EXPORT,
                "[NeoForgeEventBusBridge] addListener failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean invokeRegister(Object bus, Object listener) {
        Method method = findSingleArgMethod(bus.getClass(), "register", listener);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(bus, listener);
            return true;
        } catch (Throwable t) {
            VoxelBridgeLogger.warn(LogModule.EXPORT,
                "[NeoForgeEventBusBridge] register failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Method findSingleArgMethod(Class<?> type, String name, Object arg) {
        Method method = findMethod(type, name, arg);
        if (method != null) {
            return method;
        }
        for (Method m : type.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(arg.getClass())) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Object arg) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(arg.getClass())) {
                return m;
            }
        }
        return null;
    }
}
