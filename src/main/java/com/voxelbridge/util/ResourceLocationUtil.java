package com.voxelbridge.util;

import net.minecraft.resources.ResourceLocation;

/**
 * Sanitizes arbitrary sprite/material keys into valid {@link ResourceLocation} strings.
 */
public final class ResourceLocationUtil {

    private ResourceLocationUtil() {}

    /**
        * Sanitizes a potentially malformed key into a safe ResourceLocation-compatible string.
        * - Keeps the first ':' as namespace separator; replaces additional ':' in the path with '/'.
        * - Lowercases and strips invalid namespace chars to '_'.
        * - Replaces spaces with '_' in path.
        */
    public static String sanitizeKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "minecraft:missingno";
        }
        int idx = raw.indexOf(':');
        String ns;
        String path;
        if (idx < 0) {
            ns = "minecraft";
            path = raw;
        } else {
            ns = raw.substring(0, idx);
            path = raw.substring(idx + 1);
        }
        ns = ns.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        path = path.replace(':', '/').replace(' ', '_');
        if (path.isEmpty()) {
            path = "missingno";
        }
        return ns + ":" + path;
    }

    public static ResourceLocation sanitize(String raw) {
        return ResourceLocation.parse(sanitizeKey(raw));
    }
}
