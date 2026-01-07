package com.voxelbridge.export.texture;

import com.voxelbridge.export.ExportContext;

public final class TexturePathResolver {

    private TexturePathResolver() {}

    private static boolean isIndividualMode() {
        return com.voxelbridge.config.ExportRuntimeConfig.getAtlasMode()
            == com.voxelbridge.config.ExportRuntimeConfig.AtlasMode.INDIVIDUAL;
    }

    public static String entityTextureDir(ExportContext ctx) {
        return isIndividualMode() ? "textures/individual" : "entity_textures";
    }

    public static String ensureEntityLikePath(ExportContext ctx, String spriteKey) {
        String existing = ctx.getMaterialPaths().get(spriteKey);
        if (existing != null) {
            return existing;
        }
        String rel = entityTextureDir(ctx) + "/" + safe(spriteKey) + ".png";
        ctx.getMaterialPaths().put(spriteKey, rel);
        return rel;
    }

    public static String ensureGeneratedPath(ExportContext ctx, String spriteKey) {
        String existing = ctx.getMaterialPaths().get(spriteKey);
        if (existing != null) {
            return existing;
        }
        String rel = isIndividualMode()
            ? entityTextureDir(ctx) + "/" + safe(spriteKey) + ".png"
            : "entity_textures/generated/" + safe(spriteKey) + ".png";
        ctx.getMaterialPaths().put(spriteKey, rel);
        return rel;
    }

    public static String entityPbrPath(ExportContext ctx, String spriteKey, String suffix) {
        return entityTextureDir(ctx) + "/" + safe(spriteKey) + suffix + ".png";
    }

    public static String safe(String spriteKey) {
        String s = spriteKey.replace(':', '_').replace('/', '_');
        if (s.length() > 96) {
            return s.substring(0, 80) + "__" + Integer.toHexString(s.hashCode());
        }
        return s;
    }
}
