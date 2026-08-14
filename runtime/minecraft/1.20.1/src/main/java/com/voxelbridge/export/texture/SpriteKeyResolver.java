package com.voxelbridge.export.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.20.1-specific SpriteKey resolver.
 * Avoids reflection by parsing SpriteContents toString.
 */
public final class SpriteKeyResolver {

    private static final Pattern NAME_PATTERN = Pattern.compile("name=([^,}]+)");

    private SpriteKeyResolver() {}

    /**
     * Maps a {@link TextureAtlasSprite} to a deterministic key (e.g. minecraft:block/grass_block_top).
     */
    public static String resolve(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return "minecraft:block/unknown";
        }

        String contentsStr = String.valueOf(sprite.contents());
        String fromContents = extractName(contentsStr);
        if (fromContents != null) {
            return fromContents;
        }

        String spriteStr = String.valueOf(sprite);
        String fromSprite = extractName(spriteStr);
        if (fromSprite != null) {
            return fromSprite;
        }

        return "minecraft:block/unknown";
    }

    private static String extractName(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = NAME_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
