package com.voxelbridge.export.texture;

import com.voxelbridge.core.texture.AnimationMetadata;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Reflection-based adapter for AnimationMetadataSection across Minecraft versions.
 */
public final class AnimationMetadataUtil {

    private static volatile Object sectionKey;
    private static volatile boolean sectionKeyResolved;

    private AnimationMetadataUtil() {}

    public static AnimationMetadataSection readSection(Object metadata) {
        if (metadata == null) {
            return null;
        }
        Object key = resolveSectionKey();
        AnimationMetadataSection meta = tryGetSection(metadata, key);
        if (meta != null) {
            return meta;
        }
        return tryGetSectionByClass(metadata);
    }

    public static AnimationMetadata toCoreMetadata(AnimationMetadataSection meta) {
        if (meta == null) {
            return null;
        }
        List<AnimationMetadata.FrameTiming> timings = new ArrayList<>();
        int defaultFrameTime = getInt(meta, 1, "getDefaultFrameTime", "defaultFrameTime", "getFrameTime", "frameTime");
        boolean interpolate = getBoolean(meta, false, "isInterpolatedFrames", "isInterpolated", "interpolated", "getInterpolated");

        if (!collectForEachFrame(meta, timings, defaultFrameTime)) {
            collectFramesList(meta, timings, defaultFrameTime);
        }

        return new AnimationMetadata(defaultFrameTime, timings, interpolate, 0, 0);
    }

    public static AnimationMetadata parseMcmetaJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (!rootEl.isJsonObject()) {
                return null;
            }
            JsonObject root = rootEl.getAsJsonObject();
            JsonObject anim = root.getAsJsonObject("animation");
            if (anim == null) {
                return null;
            }

            int defaultFrameTime = getInt(anim, 1, "frametime");
            boolean interpolate = getBoolean(anim, false, "interpolate");
            int width = getInt(anim, 0, "width");
            int height = getInt(anim, 0, "height");

            List<AnimationMetadata.FrameTiming> timings = new ArrayList<>();
            JsonElement framesEl = anim.get("frames");
            if (framesEl != null && framesEl.isJsonArray()) {
                for (JsonElement entry : framesEl.getAsJsonArray()) {
                    if (entry == null || entry.isJsonNull()) {
                        continue;
                    }
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isNumber()) {
                        timings.add(new AnimationMetadata.FrameTiming(entry.getAsInt(), defaultFrameTime));
                        continue;
                    }
                    if (entry.isJsonObject()) {
                        JsonObject obj = entry.getAsJsonObject();
                        Integer idx = getInt(obj, null, "index");
                        Integer time = getInt(obj, null, "time");
                        if (idx != null) {
                            int frameTime = (time != null && time > 0) ? time : defaultFrameTime;
                            timings.add(new AnimationMetadata.FrameTiming(idx, frameTime));
                        }
                    }
                }
            }

            return new AnimationMetadata(defaultFrameTime, timings, interpolate, width, height);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean collectForEachFrame(AnimationMetadataSection meta,
                                               List<AnimationMetadata.FrameTiming> timings,
                                               int defaultFrameTime) {
        Method method = findMethod(meta.getClass(), "forEachFrame");
        if (method == null || method.getParameterCount() != 1) {
            return false;
        }
        try {
            method.invoke(meta, (BiConsumer<Integer, Integer>) (idx, time) -> {
                int frameTime = (time != null && time > 0) ? time : defaultFrameTime;
                timings.add(new AnimationMetadata.FrameTiming(idx, frameTime));
            });
            return !timings.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void collectFramesList(AnimationMetadataSection meta,
                                          List<AnimationMetadata.FrameTiming> timings,
                                          int defaultFrameTime) {
        Object framesObj = invokeNoArg(meta, "frames", "getFrames");
        if (!(framesObj instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object frame : iterable) {
            if (frame instanceof Integer idx) {
                timings.add(new AnimationMetadata.FrameTiming(idx, defaultFrameTime));
                continue;
            }
            Integer idx = getInt(frame, null, "index", "getIndex", "frameIndex", "getFrameIndex");
            Integer time = getInt(frame, null, "time", "getTime", "frameTime", "getFrameTime");
            if (idx != null) {
                int frameTime = (time != null && time > 0) ? time : defaultFrameTime;
                timings.add(new AnimationMetadata.FrameTiming(idx, frameTime));
            }
        }
    }

    private static AnimationMetadataSection tryGetSection(Object metadata, Object key) {
        if (key == null) {
            return null;
        }
        Object result = invokeGetSection(metadata, key);
        if (result instanceof Optional<?> opt) {
            Object value = opt.orElse(null);
            return (value instanceof AnimationMetadataSection) ? (AnimationMetadataSection) value : null;
        }
        return (result instanceof AnimationMetadataSection) ? (AnimationMetadataSection) result : null;
    }

    private static AnimationMetadataSection tryGetSectionByClass(Object metadata) {
        try {
            Method method = metadata.getClass().getMethod("getSection", Class.class);
            Object result = method.invoke(metadata, AnimationMetadataSection.class);
            if (result instanceof Optional<?> opt) {
                Object value = opt.orElse(null);
                return (value instanceof AnimationMetadataSection) ? (AnimationMetadataSection) value : null;
            }
            return (result instanceof AnimationMetadataSection) ? (AnimationMetadataSection) result : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeGetSection(Object metadata, Object key) {
        for (Method method : metadata.getClass().getMethods()) {
            if (!"getSection".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(key.getClass())) {
                continue;
            }
            try {
                return method.invoke(metadata, key);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object resolveSectionKey() {
        if (sectionKeyResolved) {
            return sectionKey;
        }
        synchronized (AnimationMetadataUtil.class) {
            if (sectionKeyResolved) {
                return sectionKey;
            }
            sectionKey = findSectionKey();
            sectionKeyResolved = true;
            return sectionKey;
        }
    }

    private static Object findSectionKey() {
        for (String name : new String[] {"SERIALIZER", "TYPE", "CODEC"}) {
            Object value = getStaticField(AnimationMetadataSection.class, name);
            if (value != null) {
                return value;
            }
        }
        for (Field field : AnimationMetadataSection.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String typeName = field.getType().getName();
            if (typeName.contains("MetadataSection")) {
                Object value = getStaticField(AnimationMetadataSection.class, field.getName());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Object getStaticField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Integer getInt(Object target, Integer fallback, String... names) {
        for (String name : names) {
            try {
                if (target instanceof JsonObject obj) {
                    JsonElement el = obj.get(name);
                    if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                        return el.getAsInt();
                    }
                    continue;
                }
                Method method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value instanceof Integer i) {
                    return i;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return fallback;
    }

    private static boolean getBoolean(Object target, boolean fallback, String... names) {
        for (String name : names) {
            try {
                if (target instanceof JsonObject obj) {
                    JsonElement el = obj.get(name);
                    if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                        return el.getAsBoolean();
                    }
                    continue;
                }
                Method method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value instanceof Boolean b) {
                    return b;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return fallback;
    }
}
