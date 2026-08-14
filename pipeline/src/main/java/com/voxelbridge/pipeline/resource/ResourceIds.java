package com.voxelbridge.pipeline.resource;

import com.voxelbridge.pipeline.contract.ResourceId;

import java.util.Locale;

/** Resource-name policy shared by every exact-version adapter. */
public final class ResourceIds {
    private ResourceIds() {}

    public static ResourceId sanitize(String raw) {
        return ResourceId.parse(sanitizeKey(raw));
    }

    public static String sanitizeKey(String raw) {
        if (raw == null || raw.isEmpty()) return "minecraft:missingno";
        int separator = raw.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : raw.substring(0, separator);
        String path = separator < 0 ? raw : raw.substring(separator + 1);
        namespace = sanitizeNamespace(namespace);
        path = path.replace(':', '/').replace(' ', '_');
        return namespace + ':' + (path.isEmpty() ? "missingno" : path);
    }

    private static String sanitizeNamespace(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            boolean valid = character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '.' || character == '-';
            result.append(valid ? character : '_');
        }
        return result.toString();
    }
}
