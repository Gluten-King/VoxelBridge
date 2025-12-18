package com.voxelbridge.modhandler.ctm;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Properties;

/**
 * Reflection utilities for Continuity mod CTM properties.
 * Extracts CTM configuration from Continuity's internal model structures.
 */
public final class ContinuityReflector {

    private ContinuityReflector() {}

    /**
     * Attempts to extract CTM overlay information from a model using reflection.
     *
     * @param model the baked model (potentially a Continuity CTM model)
     * @param spriteName sprite resource location
     * @param spriteKey resolved sprite key
     * @return CTM overlay info if detected, null otherwise
     */
    public static CtmDetector.CtmOverlayInfo tryReflectCtm(Object model, ResourceLocation spriteName, String spriteKey) {
        if (model == null) return null;

        Class<?> cls = model.getClass();
        if (!cls.getName().toLowerCase(Locale.ROOT).contains("continuity")) return null;

        Properties props = null;

        // Scan declared fields for properties-like object
        for (var field : cls.getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(model);
                if (value == null) continue;

                String vCls = value.getClass().getName();

                if (value instanceof Properties p) {
                    props = p;
                    break;
                }

                if (vCls.toLowerCase(Locale.ROOT).contains("ctmproperties") || vCls.toLowerCase(Locale.ROOT).contains("properties")) {
                    props = extractPropertiesFromObject(value);
                    break;
                }
            } catch (Throwable t) {
                // Reflection failed for this field
            }
        }

        if (props == null) return null;

        String method = props.getProperty("method", "").trim().toLowerCase(Locale.ROOT);
        String tiles = props.getProperty("tiles", "").trim();
        String connectTiles = props.getProperty("connectTiles", "").trim();

        int tileIndex = parseTileIndex(spriteName);
        boolean tileMatch = matchesTile(tiles, tileIndex);
        boolean isOverlay = "overlay".equals(method) && tileMatch;

        String baseMaterialKey = connectTiles.isEmpty() ? null : (connectTiles.contains(":") ? connectTiles : "minecraft:" + connectTiles);

        if (isOverlay) {
            return new CtmDetector.CtmOverlayInfo(true, baseMaterialKey, "reflected:" + cls.getName(), tileIndex >= 0 ? tileIndex : null);
        }

        return null;
    }

    private static int parseTileIndex(ResourceLocation spriteName) {
        String file = spriteName.getPath();
        int idx = file.lastIndexOf('/');
        if (idx >= 0) file = file.substring(idx + 1);
        try {
            return Integer.parseInt(file.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean matchesTile(String tiles, int tileIndex) {
        if (tileIndex < 0) return true;
        String[] parts = tiles.split("[ ,]");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] range = p.split("-");
                try {
                    int start = Integer.parseInt(range[0]);
                    int end = Integer.parseInt(range[1]);
                    if (tileIndex >= start && tileIndex <= end) return true;
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    if (tileIndex == Integer.parseInt(p)) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    private static Properties extractPropertiesFromObject(Object obj) {
        Properties p = new Properties();
        readProperty(obj, "method", p);
        readProperty(obj, "tiles", p);
        readProperty(obj, "connectTiles", p);
        return p;
    }

    private static void readProperty(Object obj, String name, Properties p) {
        try {
            var cls = obj.getClass();
            // Try field first
            try {
                var f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) p.setProperty(name, v.toString());
                return;
            } catch (NoSuchFieldException ignored) {}

            // Try getter method
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                var m = cls.getDeclaredMethod(getter);
                m.setAccessible(true);
                Object v = m.invoke(obj);
                if (v != null) p.setProperty(name, v.toString());
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            // Property read failed
        }
    }
}
