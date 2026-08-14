package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
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
        ResourceLocation base = RenderTypeTextureResolver.INSTANCE.resolve(renderType);
        if (base == null) {
            return null;
        }
        // Some renderers produce paths with an extra ':' inside (e.g. "textures:models/...").
        if (base.getPath().contains(":")) {
            base = new ResourceLocation(base.getNamespace(), base.getPath().replace(':', '/'));
        }
        return resolveTextureWithAtlasDetection(base);
    }

    private static ResolvedTexture resolveEntitySpecific(Entity entity, RenderType renderType) {
        if (entity instanceof ItemFrame) {
            ResolvedTexture helper = Adapters.getTextureHelper().resolveEntityTexture(entity, renderType);
            if (helper != null) {
                return helper;
            }
            ResourceLocation base = RenderTypeTextureResolver.INSTANCE.resolve(renderType);
            if (base != null) {
                if (base.getPath().contains(":")) {
                    base = new ResourceLocation(base.getNamespace(), base.getPath().replace(':', '/'));
                }
                return resolveTextureWithAtlasDetection(base);
            }
            return null;
        }
        return Adapters.getTextureHelper().resolveEntityTexture(entity, renderType);
    }

    private static ResolvedTexture resolveTextureWithAtlasDetection(ResourceLocation texture) {
        // Handle known atlases first (chest/sign/bed, etc.) although entities rarely use them.
        ResourceLocation[] knownAtlases = {
            Sheets.CHEST_SHEET,
            Sheets.BED_SHEET,
            Sheets.SIGN_SHEET,
            new ResourceLocation("minecraft", "textures/atlas/decorated_pot.png"),
            new ResourceLocation("minecraft", "textures/atlas/shulker_boxes.png"),
            new ResourceLocation("minecraft", "textures/atlas/banner_patterns.png"),
            new ResourceLocation("minecraft", "textures/atlas/shield_patterns.png")
        };

        for (ResourceLocation atlas : knownAtlases) {
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
}

