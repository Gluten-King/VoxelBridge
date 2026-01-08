package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderLayerTextureResolver;
import com.voxelbridge.platform.texture.TextureLoader;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.MapRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.texture.Sprite;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

/**
 * Resolves textures for entity renderers with entity-specific overrides.
 */
public final class EntityTextureResolver implements TextureResolver<Entity> {

    public static final EntityTextureResolver INSTANCE = new EntityTextureResolver();

    private EntityTextureResolver() {}

    @Override
    public ResolvedTexture resolve(Entity entity, RenderLayer renderLayer) {
        ResolvedTexture specific = resolveEntitySpecific(entity, renderLayer);
        if (specific != null) {
            return specific;
        }

        Identifier base = RenderLayerTextureResolver.INSTANCE.resolve(renderLayer);
        if (base == null) {
            return null;
        }
        if (base.getPath().contains(":")) {
            base = Identifier.of(base.getNamespace(), base.getPath().replace(':', '/'));
        }
        return resolveTextureWithAtlasDetection(base);
    }

    private static ResolvedTexture resolveEntitySpecific(Entity entity, RenderLayer renderLayer) {
        if (entity instanceof PaintingEntity painting) {
            return resolvePaintingTexture(painting);
        }
        if (entity instanceof ItemFrameEntity itemFrame) {
            return resolveItemFrameTexture(itemFrame, renderLayer);
        }
        return null;
    }

    private static ResolvedTexture resolvePaintingTexture(PaintingEntity painting) {
        try {
            RegistryEntry<PaintingVariant> variantHolder = painting.getVariant();
            PaintingVariant variant = variantHolder.value();
            Identifier texture = variant.assetId();

            try {
                Sprite backSprite = ClientAccessHolder.get().getPaintingTextures().getBackSprite();
                if (backSprite != null) {
                    Identifier atlas = backSprite.getAtlasId();
                    VoxelBridgeLogger.debug(LogModule.ENTITY,
                        "[Painting] Using atlas locator for painting atlas: " + atlas);
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

            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                painting.getType(), "painting_variant", variant.assetId()));
            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                painting.getType(), "painting_size", variant.width() + "x" + variant.height()));

            if (!texture.getPath().startsWith("textures/")) {
                texture = Identifier.of(texture.getNamespace(), "textures/painting/" + texture.getPath() + ".png");
            }

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[Painting] Resolved texture: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ENTITY,
                "[EntityTextureResolver] Failed to resolve painting texture: " + e.getMessage());
            return null;
        }
    }

    private static ResolvedTexture resolveItemFrameTexture(ItemFrameEntity itemFrame, RenderLayer renderLayer) {
        try {
            ItemStack item = itemFrame.getHeldItemStack();
            boolean hasItem = item != null && !item.isEmpty();

            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                itemFrame.getType(), "has_item", hasItem));
            if (hasItem) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[EntityData] %s %s=%s",
                    itemFrame.getType(), "item", item.getItem()));
            }
            VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                "[EntityData] %s %s=%s",
                itemFrame.getType(), "direction", itemFrame.getHorizontalFacing()));

            if (hasItem && item.getItem() instanceof FilledMapItem) {
                Identifier mapTexId = resolveMapTextureId(itemFrame.getWorld(), item);
                if (mapTexId != null) {
                    VoxelBridgeLogger.debug(LogModule.ENTITY,
                        "[ItemFrame] Using map texture: " + mapTexId);
                    return new ResolvedTexture(mapTexId, 0f, 1f, 0f, 1f, false, null, null);
                }
            }

            Identifier base = RenderLayerTextureResolver.INSTANCE.resolve(renderLayer);
            if (base != null) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[ItemFrame] Resolved texture from RenderLayer: " + base);
                return resolveTextureWithAtlasDetection(base);
            }

            boolean isGlowFrame = itemFrame instanceof net.minecraft.entity.decoration.GlowItemFrameEntity;
            String framePath = isGlowFrame ?
                "textures/entity/glow_item_frame.png" :
                "textures/entity/item_frame.png";
            Identifier frameTexture = Identifier.of("minecraft", framePath);

            VoxelBridgeLogger.debug(LogModule.ENTITY, "[ItemFrame] Fallback frame texture: " + frameTexture);
            return new ResolvedTexture(frameTexture, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ENTITY,
                "[EntityTextureResolver] Failed to resolve item frame texture: " + e.getMessage());
            return null;
        }
    }

    private static Identifier resolveMapTextureId(net.minecraft.world.World world, ItemStack item) {
        try {
            VoxelBridgeLogger.debug(LogModule.MAP, String.format(
                "[Map] resolveMapTextureId item=%s world=%s",
                item != null ? item.getItem() : "null",
                world != null ? world.getRegistryKey().getValue() : "null"));
            if (item == null || world == null) {
                VoxelBridgeLogger.debug(LogModule.MAP, "[Map] missing item or world");
                return null;
            }
            MapIdComponent mapId = item.get(DataComponentTypes.MAP_ID);
            if (mapId == null) {
                VoxelBridgeLogger.debug(LogModule.MAP, "[Map] mapId=null");
                return null;
            }
            MapState mapState = FilledMapItem.getMapState(mapId, world);
            if (mapState == null) {
                VoxelBridgeLogger.debug(LogModule.MAP, "[Map] mapState=null");
                return null;
            }
            TextureLoader.registerMapState(mapId.id(), mapState);
            MinecraftClient client = MinecraftClient.getInstance();
            MapRenderer mapRenderer = client != null && client.gameRenderer != null
                ? client.gameRenderer.getMapRenderer()
                : null;
            if (mapRenderer != null) {
                mapRenderer.updateTexture(mapId, mapState);
                VoxelBridgeLogger.debug(LogModule.MAP, String.format(
                    "[Map] updated renderer=%s mapId=%s",
                    mapRenderer.getClass().getSimpleName(), mapId.asString()));
            } else {
                VoxelBridgeLogger.debug(LogModule.MAP, String.format(
                    "[Map] renderer=null mapId=%s",
                    mapId.asString()));
            }
            Identifier id = Identifier.of("minecraft", "map/" + mapId.id());
            VoxelBridgeLogger.debug(LogModule.MAP, String.format(
                "[Map] resolved id=%s",
                id));
            return id;
        } catch (Exception ignored) {
            VoxelBridgeLogger.debug(LogModule.MAP, "[Map] resolveMapTextureId failed with exception");
            return null;
        }
    }

    private static ResolvedTexture resolveTextureWithAtlasDetection(Identifier texture) {
        Identifier[] knownAtlases = {
            TexturedRenderLayers.CHEST_ATLAS_TEXTURE,
            TexturedRenderLayers.BEDS_ATLAS_TEXTURE,
            TexturedRenderLayers.SIGNS_ATLAS_TEXTURE,
            TexturedRenderLayers.DECORATED_POT_ATLAS_TEXTURE,
            TexturedRenderLayers.SHULKER_BOXES_ATLAS_TEXTURE,
            TexturedRenderLayers.BANNER_PATTERNS_ATLAS_TEXTURE,
            TexturedRenderLayers.SHIELD_PATTERNS_ATLAS_TEXTURE
        };

        for (Identifier atlas : knownAtlases) {
            try {
                var atlasGetter = ClientAccessHolder.get().getTextureAtlas(atlas);
                if (atlasGetter != null) {
                    Sprite sprite = atlasGetter.apply(texture);
                    if (sprite != null && !isMissingSprite(sprite)) {
                        return new ResolvedTexture(texture, sprite.getMinU(), sprite.getMaxU(),
                            sprite.getMinV(), sprite.getMaxV(), true, sprite, atlas);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    private static boolean isMissingSprite(Sprite sprite) {
        return sprite.getContents().getId().toString().contains("missingno");
    }
}
