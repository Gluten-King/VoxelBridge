package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Resolved texture reference with optional atlas bounds and sprite info.
 */
public record ResolvedTexture(
    Identifier texture,
    float u0,
    float u1,
    float v0,
    float v1,
    boolean isAtlasTexture,
    TextureAtlasSprite sprite,
    Identifier atlasLocation
) {
    public static ResolvedTexture fromSprite(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) {
            return null;
        }
        return new ResolvedTexture(
            sprite.contents().name(),
            sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
            true, sprite, sprite.atlasLocation()
        );
    }
}
