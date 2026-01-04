package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.blockentity.RenderTypeTextureResolver;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

/**
 * Resolves textures for entity renderers with entity-specific overrides.
 */
@OnlyIn(Dist.CLIENT)
public final class EntityTextureResolver {

    public record ResolvedTexture(ResourceLocation texture, float u0, float u1, float v0, float v1,
                                  boolean isAtlasTexture, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                                  ResourceLocation atlasLocation) {}

    private EntityTextureResolver() {}

    public static ResolvedTexture resolve(Entity entity, RenderType renderType) {
        // Try entity-specific resolvers first
        ResolvedTexture specific = resolveEntitySpecific(entity, renderType);
        if (specific != null) {
            return specific;
        }

        // Fall back to generic RenderType-based resolution
        ResourceLocation base = RenderTypeTextureResolver.resolve(renderType);
        if (base == null) {
            return null;
        }
        // Some renderers produce paths with an extra ':' inside (e.g. "textures:models/...").
        if (base.getPath().contains(":")) {
            base = ResourceLocation.fromNamespaceAndPath(base.getNamespace(), base.getPath().replace(':', '/'));
        }

        // Check if this is an atlas texture
        if (isAtlasPath(base)) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityTextureResolver] Detected atlas texture: " + base);
            return new ResolvedTexture(base, 0f, 1f, 0f, 1f, true, null, base);
        }

        return resolveTextureWithAtlasDetection(base);
    }

    public static ResolvedTexture resolveFallback(ResourceLocation texture) {
        if (texture == null) {
            return null;
        }
        return resolveTextureWithAtlasDetection(texture);
    }

    private static ResolvedTexture resolveEntitySpecific(Entity entity, RenderType renderType) {
        // Handle Painting entities
        if (entity instanceof Painting painting) {
            return resolvePaintingTexture(painting);
        }
        // ItemFrame no longer needs special handling - RenderType resolution works correctly
        return null;
    }

    private static ResolvedTexture resolvePaintingTexture(Painting painting) {
        try {
            // Paintings use the painting atlas; let the atlas locator choose the correct sprite per-quad
            var paintingAtlas = net.minecraft.client.Minecraft.getInstance().getPaintingTextures();
            var backSprite = paintingAtlas.getBackSprite();
            if (backSprite != null) {
                ResourceLocation atlas = backSprite.atlasLocation();
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[Painting] Using atlas locator for painting atlas: " + atlas);
                return new ResolvedTexture(
                    atlas,
                    0f, 1f, 0f, 1f,
                    true,
                    null,
                    atlas
                );
            }
        } catch (Exception e) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[Painting] Atlas lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static ResolvedTexture resolveTextureWithAtlasDetection(ResourceLocation texture) {
        // Handle known atlases first (chest/sign/bed, etc.) although entities rarely use them.
        ResourceLocation[] knownAtlases = {
            Sheets.CHEST_SHEET,
            Sheets.BED_SHEET,
            Sheets.SIGN_SHEET,
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/decorated_pot.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/shulker_boxes.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/shield_patterns.png")
        };

        for (ResourceLocation atlas : knownAtlases) {
            try {
                var atlasGetter = net.minecraft.client.Minecraft.getInstance().getTextureAtlas(atlas);
                if (atlasGetter != null) {
                    var sprite = atlasGetter.apply(texture);
                    if (sprite != null && !isMissingSprite(sprite)) {
                        return new ResolvedTexture(texture, sprite.getU0(), sprite.getU1(),
                            sprite.getV0(), sprite.getV1(), true, sprite, atlas);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Not found in atlases; if path hints an atlas, treat as atlas bounds, otherwise standalone
        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    private static boolean isMissingSprite(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return sprite.contents().name().toString().contains("missingno");
    }

    private static boolean isAtlasPath(ResourceLocation texture) {
        if (texture == null) {
            return false;
        }
        String path = texture.getPath();
        // Check for common atlas patterns
        return path.contains("textures/atlas/") ||
               path.equals("textures/atlas/blocks.png") ||
               path.equals("textures/atlas/signs.png") ||
               path.equals("textures/atlas/paintings.png") ||
               path.equals("textures/atlas/particles.png") ||
               path.equals("textures/atlas/mob_effects.png");
    }
}
