package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.blockentity.RenderTypeTextureResolver;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.item.ItemStack;
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
        return resolveTextureWithAtlasDetection(base);
    }

    private static ResolvedTexture resolveEntitySpecific(Entity entity, RenderType renderType) {
        // Handle Painting entities
        if (entity instanceof Painting painting) {
            return resolvePaintingTexture(painting);
        }
        // Handle ItemFrame entities (including GlowItemFrame)
        if (entity instanceof ItemFrame itemFrame) {
            return resolveItemFrameTexture(itemFrame, renderType);
        }
        return null;
    }

    private static ResolvedTexture resolvePaintingTexture(Painting painting) {
        try {
            // getVariant() returns Holder<PaintingVariant>, not Optional
            Holder<PaintingVariant> variantHolder = painting.getVariant();
            PaintingVariant variant = variantHolder.value();
            ResourceLocation texture = variant.assetId();

            // Paintings use the painting atlas; let the atlas locator choose the correct sprite per-quad
            try {
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
                // Fall back to direct texture resolution
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[Painting] Atlas lookup failed: " + e.getMessage());
            }

            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                painting.getType(), "painting_variant", variant.assetId()));
            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                painting.getType(), "painting_size", variant.width() + "x" + variant.height()));

            // Paintings use textures from textures/painting/ directory
            if (!texture.getPath().startsWith("textures/")) {
                texture = ResourceLocation.fromNamespaceAndPath(
                    texture.getNamespace(),
                    "textures/painting/" + texture.getPath() + ".png"
                );
            }

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[Painting] Resolved texture: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ENTITY, "[EntityTextureResolver] Failed to resolve painting texture: " + e.getMessage());
            return null;
        }
    }

    private static ResolvedTexture resolveItemFrameTexture(ItemFrame itemFrame, RenderType renderType) {
        try {
            ItemStack item = itemFrame.getItem();
            boolean hasItem = item != null && !item.isEmpty();

            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                itemFrame.getType(), "has_item", hasItem));
            if (hasItem) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[EntityData] %s %s=%s",
                    itemFrame.getType(), "item", item.getItem().toString()));
            }
            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                itemFrame.getType(), "direction", itemFrame.getDirection()));

            // Item frames use the block atlas; resolve to an atlas sprite via RenderType first.
            ResourceLocation base = RenderTypeTextureResolver.resolve(renderType);
            if (base != null) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[ItemFrame] Resolved texture from RenderType: " + base);
                return resolveTextureWithAtlasDetection(base);
            }

            // Fallback to entity texture if RenderType is not atlas-backed.
            boolean isGlowFrame = itemFrame instanceof net.minecraft.world.entity.decoration.GlowItemFrame;
            String framePath = isGlowFrame ?
                "textures/entity/glow_item_frame.png" :
                "textures/entity/item_frame.png";
            ResourceLocation frameTexture = ResourceLocation.fromNamespaceAndPath("minecraft", framePath);

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[ItemFrame] Fallback frame texture: " + frameTexture);
            return new ResolvedTexture(frameTexture, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ENTITY, "[EntityTextureResolver] Failed to resolve item frame texture: " + e.getMessage());
            return null;
        }
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
}
