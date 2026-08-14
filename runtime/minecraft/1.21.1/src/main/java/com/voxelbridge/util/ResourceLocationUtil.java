package com.voxelbridge.util;

import com.voxelbridge.pipeline.resource.ResourceIds;
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
        return ResourceIds.sanitizeKey(raw);
    }

    public static ResourceLocation sanitize(String raw) {
        return ResourceLocation.parse(sanitizeKey(raw));
    }
}
