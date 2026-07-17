package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.export.texture.MapTextureUtil;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Resolves textures for entity renderers with entity-specific overrides.
 */
public final class EntityTextureResolver implements TextureResolver<Entity> {

    public static final EntityTextureResolver INSTANCE = new EntityTextureResolver();

    private EntityTextureResolver() {}

    @Override
    public ResolvedTexture resolve(Entity entity, RenderType renderType) {
        // Try entity-specific resolvers first
        ResolvedTexture specific = resolveEntitySpecific(entity, renderType);
        if (specific != null) {
            return specific;
        }

        // Fall back to generic RenderType-based resolution
        Identifier base = RenderTypeTextureResolver.INSTANCE.resolve(renderType);
        if (base == null) {
            return null;
        }
        if (isTextRenderType(renderType)) {
            base = normalizeTextTexture(base, renderType);
        }
        // Some renderers produce paths with an extra ':' inside (e.g. "textures:models/...").
        if (base.getPath().contains(":")) {
            base = Identifier.fromNamespaceAndPath(base.getNamespace(), base.getPath().replace(':', '/'));
        }
        return resolveTextureWithAtlasDetection(base);
    }

    private static ResolvedTexture resolveEntitySpecific(Entity entity, RenderType renderType) {
        if (entity instanceof ItemFrame frame) {
            ResolvedTexture map = MapTextureUtil.resolveItemFrameMap(frame, renderType);
            if (map != null) {
                return map;
            }
            Identifier base = RenderTypeTextureResolver.INSTANCE.resolve(renderType);
            if (base != null) {
                if (isTextRenderType(renderType)) {
                    base = normalizeTextTexture(base, renderType);
                }
                if (base.getPath().contains(":")) {
                    base = Identifier.fromNamespaceAndPath(base.getNamespace(), base.getPath().replace(':', '/'));
                }
                return resolveTextureWithAtlasDetection(base);
            }
            return Adapters.getTextureHelper().resolveEntityTexture(entity, renderType);
        }
        return Adapters.getTextureHelper().resolveEntityTexture(entity, renderType);
    }

    private static ResolvedTexture resolveTextureWithAtlasDetection(Identifier texture) {
        // Handle known atlases first (chest/sign/bed, etc.) although entities rarely use them.
        Identifier[] knownAtlases = {
            Sheets.CHEST_SHEET,
            Sheets.BED_SHEET,
            Sheets.SIGN_SHEET,
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/decorated_pot.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/shulker_boxes.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/shield_patterns.png")
        };

        for (Identifier atlas : knownAtlases) {
            try {
                var atlasGetter = ClientAccessHolder.get().getTextureAtlas(atlas);
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

    private static boolean isTextRenderType(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);
        return name.contains("text_")
            || name.contains("neoforge_text")
            || name.contains("font")
            || name.contains("glyph");
    }

    private static Identifier normalizeTextTexture(Identifier base, RenderType renderType) {
        Identifier fromRenderType = extractFontTextureFromRenderType(renderType);
        if (fromRenderType != null) {
            return fromRenderType;
        }
        if (base == null) {
            return null;
        }
        String path = base.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("font/")) {
            return base;
        }
        return base;
    }

    private static Identifier extractFontTextureFromRenderType(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        String raw = renderType.toString();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.toLowerCase(java.util.Locale.ROOT);

        java.util.regex.Matcher dyn = java.util.regex.Pattern
            .compile("([a-z0-9_.-]+:font/[a-z0-9_./-]+)")
            .matcher(s);
        if (dyn.find()) {
            try {
                return Identifier.parse(dyn.group(1));
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
