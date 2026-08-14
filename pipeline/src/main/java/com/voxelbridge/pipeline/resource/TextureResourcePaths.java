package com.voxelbridge.pipeline.resource;

import com.voxelbridge.pipeline.contract.ResourceId;

/** Canonical conversion from exported sprite/material keys to PNG resources. */
public final class TextureResourcePaths {
    private TextureResourcePaths() {}

    public static ResourceId fromSpriteKey(String spriteKey) {
        ResourceId sanitized = ResourceIds.sanitize(spriteKey);
        String namespace = sanitized.namespace();
        String path = sanitized.path().replace('\\', '/');
        if ("blockentity".equals(namespace) || "entity".equals(namespace)) {
            int split = path.indexOf('/');
            String actualNamespace = split > 0 ? path.substring(0, split) : "minecraft";
            String actualPath = split > 0 ? path.substring(split + 1) : path;
            return new ResourceId(
                sanitizeNamespace(actualNamespace),
                sanitizePath(ensurePngExtension(ensureTexturesPrefix(actualPath))));
        }
        String normalized;
        if (path.startsWith("textures/")) normalized = ensurePngExtension(path);
        else if (path.contains("/")) normalized = ensurePngExtension("textures/" + path);
        else normalized = ensurePngExtension("textures/block/" + path);
        return new ResourceId(sanitizeNamespace(namespace), sanitizePath(normalized));
    }

    public static ResourceId ensurePng(String resourceKey) {
        ResourceId resource = ResourceIds.sanitize(resourceKey);
        String path = resource.path();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return new ResourceId(resource.namespace(), sanitizePath(path));
    }

    public static ResourceId appendSuffix(String resourceKey, String suffix) {
        ResourceId resource = ensurePng(resourceKey);
        String path = resource.path();
        String base = path.substring(0, path.length() - ".png".length());
        return new ResourceId(resource.namespace(), base + (suffix == null ? "" : suffix) + ".png");
    }

    public static ResourceId generated(String namespace, String path) {
        String safeNamespace = namespace == null || namespace.isBlank() ? "minecraft" : namespace;
        String safePath = path == null ? "generated/missingno.png" : path;
        return new ResourceId(sanitizeNamespace(safeNamespace), sanitizePath(safePath));
    }

    private static String ensureTexturesPrefix(String path) {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return clean.startsWith("textures/") ? clean : "textures/" + clean;
    }

    private static String ensurePngExtension(String path) {
        return path.endsWith(".png") ? path : path + ".png";
    }

    private static String sanitizePath(String path) {
        StringBuilder result = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean valid = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '/' || c == '.' || c == '_' || c == '-';
            result.append(valid ? c : '_');
        }
        return result.toString();
    }

    private static String sanitizeNamespace(String namespace) {
        StringBuilder result = new StringBuilder(namespace.length());
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean valid = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '.' || c == '_' || c == '-';
            result.append(valid ? c : '_');
        }
        return result.isEmpty() ? "minecraft" : result.toString();
    }
}
