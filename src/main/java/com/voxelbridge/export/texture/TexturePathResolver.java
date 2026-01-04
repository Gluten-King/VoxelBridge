package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;

public final class TexturePathResolver {

    private TexturePathResolver() {}

    public static String ensureEntityLikePath(ExportContext ctx, String spriteKey) {
        String existing = ctx.getMaterialPaths().get(spriteKey);
        if (existing != null) {
            return existing;
        }
        String rel = "textures/entity_textures/" + safe(spriteKey) + ".png";
        ctx.getMaterialPaths().put(spriteKey, rel);
        return rel;
    }

    public static String ensureGeneratedPath(ExportContext ctx, String spriteKey) {
        String existing = ctx.getMaterialPaths().get(spriteKey);
        if (existing != null) {
            return existing;
        }
        String rel = "textures/entity_textures/generated/" + safe(spriteKey) + ".png";
        ctx.getMaterialPaths().put(spriteKey, rel);
        return rel;
    }

    public static String safe(String spriteKey) {
        String safe = spriteKey.replace(':', '_').replace('/', '_');
        if (safe.endsWith(".png")) {
            safe = safe.substring(0, safe.length() - 4);
        }
        return safe;
    }
}
