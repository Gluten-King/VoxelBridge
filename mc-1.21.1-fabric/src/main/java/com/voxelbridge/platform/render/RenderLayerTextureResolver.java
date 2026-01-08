package com.voxelbridge.platform.render;

import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Resolves texture identifiers from RenderLayer instances using reflection.
 */
public final class RenderLayerTextureResolver implements RenderTypeResolver {

    public static final RenderLayerTextureResolver INSTANCE = new RenderLayerTextureResolver();

    private RenderLayerTextureResolver() {}

    @Override
    public Identifier resolve(RenderLayer renderType) {
        if (renderType == null) {
            return null;
        }

        Identifier fromState = extractFromState(renderType);
        if (fromState != null) {
            logTextRenderType(renderType, fromState);
            return sanitize(fromState);
        }

        try {
            Identifier extracted = extractTextureViaReflection(renderType);
            if (extracted != null) {
                logTextRenderType(renderType, extracted);
                return sanitize(extracted);
            }
            Identifier fromFields = extractFromRenderTypeFields(renderType);
            if (fromFields != null) {
                logTextRenderType(renderType, fromFields);
                return sanitize(fromFields);
            }
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, "[RenderLayerTextureResolver] Reflection failed for " +
                renderType + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        logTextRenderType(renderType, null);
        return null;
    }

    @Override
    public boolean isDoubleSided(RenderLayer renderType) {
        try {
            Object phases = multiPhaseParameters(renderType);
            if (phases == null) {
                return false;
            }
            Field cullField = phases.getClass().getDeclaredField("cull");
            cullField.setAccessible(true);
            Object cullState = cullField.get(phases);
            if (cullState == null) {
                return false;
            }
            Class<?> toggleable = cullState.getClass().getSuperclass(); // RenderPhase$Toggleable
            Field enabled = toggleable.getDeclaredField("enabled");
            enabled.setAccessible(true);
            boolean cullEnabled = enabled.getBoolean(cullState);
            return !cullEnabled;
        } catch (Exception e) {
            return false;
        }
    }

    private static Identifier extractFromState(RenderLayer renderType) {
        try {
            Object phases = multiPhaseParameters(renderType);
            if (phases == null) {
                return null;
            }

            Field textureField = phases.getClass().getDeclaredField("texture");
            textureField.setAccessible(true);
            Object textureState = textureField.get(phases);
            if (textureState == null) {
                return null;
            }

            Method getId = textureState.getClass().getDeclaredMethod("getId");
            getId.setAccessible(true);
            Object result = getId.invoke(textureState);
            if (result instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof Identifier loc) {
                return loc;
            }
            return extractFromTextureState(textureState);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Identifier extractFromTextureState(Object textureState) {
        if (textureState == null) {
            return null;
        }
        for (String methodName : new String[] {"getId", "texture", "textureLocation", "getTexture", "getTextureLocation", "location", "getLocation"}) {
            try {
                Method method = textureState.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object value = method.invoke(textureState);
                Identifier loc = unwrapLocation(value);
                if (loc != null) {
                    return loc;
                }
            } catch (Exception ignored) {
            }
        }
        for (String fieldName : new String[] {"texture", "location", "resourceLocation", "loc"}) {
            try {
                Field field = textureState.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(textureState);
                Identifier loc = unwrapLocation(value);
                if (loc != null) {
                    return loc;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Identifier unwrapLocation(Object value) {
        if (value instanceof Optional<?> opt) {
            value = opt.orElse(null);
        }
        return value instanceof Identifier loc ? loc : null;
    }

    private static Object multiPhaseParameters(RenderLayer renderType) {
        try {
            Class<?> multiPhaseClass = Class.forName("net.minecraft.client.render.RenderLayer$MultiPhase");
            if (!multiPhaseClass.isInstance(renderType)) {
                return null;
            }

            Method phasesMethod = multiPhaseClass.getDeclaredMethod("getPhases");
            phasesMethod.setAccessible(true);
            return phasesMethod.invoke(renderType);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Identifier extractTextureViaReflection(RenderLayer renderType) {
        try {
            String name = renderType.toString();
            if (name.contains("RenderLayer[")) {
                int texIdx = name.indexOf("texture=");
                if (texIdx >= 0) {
                    int start = texIdx + 8;
                    int end = name.indexOf(",", start);
                    if (end < 0) end = name.indexOf("]", start);
                    if (end > start) {
                        String texStr = name.substring(start, end).trim();
                        return Identifier.tryParse(texStr);
                    }
                }
            }
            String optional = parseOptionalTexture(name);
            if (optional != null) {
                return Identifier.tryParse(optional);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Identifier extractFromRenderTypeFields(RenderLayer renderType) {
        if (renderType == null) {
            return null;
        }
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        return scanForLocation(renderType, 2, seen);
    }

    private static Identifier scanForLocation(Object value, int depth,
                                              java.util.IdentityHashMap<Object, Boolean> seen) {
        if (value == null || depth < 0) {
            return null;
        }
        if (value instanceof Identifier loc) {
            return loc;
        }
        if (value instanceof Optional<?> opt) {
            Object inner = opt.orElse(null);
            if (inner instanceof Identifier loc) {
                return loc;
            }
        }
        String typeName = value.getClass().getName();
        if (typeName.startsWith("java.") || typeName.startsWith("javax.")
            || typeName.startsWith("sun.") || typeName.startsWith("jdk.")) {
            return null;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Identifier loc = scanForLocation(item, depth - 1, seen);
                if (loc != null) {
                    return loc;
                }
            }
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                Identifier loc = scanForLocation(item, depth - 1, seen);
                if (loc != null) {
                    return loc;
                }
            }
            return null;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            return null;
        }
        while (type != null && type != Object.class) {
            Field[] fields = type.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    Identifier loc = scanForLocation(fieldValue, depth - 1, seen);
                    if (loc != null) {
                        return loc;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            type = type.getSuperclass();
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

    private static Identifier sanitize(Identifier loc) {
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
        return Identifier.of(namespace, path);
    }

    private static void logTextRenderType(RenderLayer renderType, Identifier loc) {
        if (renderType == null) {
            return;
        }
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);
        boolean isText = name.contains("text_")
            || name.contains("font")
            || name.contains("glyph");
        if (!isText) {
            return;
        }
        VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
            "[RenderLayerTextureResolver] text renderType=" + renderType + " resolved=" + loc);
    }
}
