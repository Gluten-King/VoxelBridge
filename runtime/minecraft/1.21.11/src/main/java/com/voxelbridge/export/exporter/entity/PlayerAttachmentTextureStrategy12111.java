package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.platform.texture.TextureLoader;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.Locale;

/** Minecraft 1.21.11 player cape/elytra texture adapter. */
final class PlayerAttachmentTextureStrategy12111 {
    private PlayerAttachmentTextureStrategy12111() {}

    static EntityTextureManager.TextureHandle register(ExportContext context,
                                                       AbstractClientPlayer player,
                                                       Identifier texture) {
        String type = detectType(texture);
        if (type == null) return null;
        BufferedImage image = readWithFallback(context, texture);
        if (image == null) return null;
        String playerName = sanitize(player.getGameProfile().name());
        return EntityTextureManager.registerGenerated(
            context,
            "entity:player/" + type + "/" + playerName,
            "textures/entity_textures/player/" + playerName + "_" + type + ".png",
            image);
    }

    private static String detectType(Identifier texture) {
        if (texture == null) return null;
        String path = texture.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("elytra")) return "elytra";
        if (path.contains("cape") || path.contains("cloak")) return "cape";
        return null;
    }

    private static BufferedImage readWithFallback(ExportContext context, Identifier texture) {
        BufferedImage image = TextureLoader.readTexture(texture, context.textureOptions().animationEnabled());
        if (image != null) return image;
        Identifier fallback = fallbackPath(texture);
        return fallback.equals(texture)
            ? null
            : TextureLoader.readTexture(fallback, context.textureOptions().animationEnabled());
    }

    private static Identifier fallbackPath(Identifier texture) {
        String path = texture.getPath();
        if (path.startsWith("skins/") || path.startsWith("skin/")) return texture;
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return Identifier.fromNamespaceAndPath(texture.getNamespace(), path);
    }

    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "player";
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char value = lower.charAt(i);
            boolean valid = value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '.' || value == '-' || value == '_';
            result.append(valid ? value : '_');
        }
        return result.toString();
    }
}
