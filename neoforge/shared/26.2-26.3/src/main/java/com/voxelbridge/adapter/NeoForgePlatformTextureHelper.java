package com.voxelbridge.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.mixin.SpriteContentsAccessor;
import com.voxelbridge.mixin.TextureAtlasAccessor;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class NeoForgePlatformTextureHelper implements PlatformTextureHelper {
    @Override
    public int getPixelRgba(NativeImage image, int x, int y) {
        if (image == null) return 0;
        int abgr = image.getPixel(x, y);
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public NativeImage getOriginalImage(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.contents() == null) return null;
        try {
            return ((SpriteContentsAccessor) (Object) sprite.contents()).voxelbridge$getOriginalImage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public Collection<TextureAtlasSprite> getAllSprites(TextureAtlas atlas) {
        if (atlas == null) return Collections.emptyList();
        try {
            Map<Identifier, TextureAtlasSprite> sprites =
                    ((TextureAtlasAccessor) (Object) atlas).voxelbridge$getTexturesByName();
            return sprites != null ? sprites.values() : Collections.emptyList();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    @Override
    public Optional<NativeImage> readTexture(Identifier location) {
        return NeoForgeDynamicTextureReader.INSTANCE.readTexture(location);
    }

    @Override
    public void copyNativeImage(NativeImage source, NativeImage destination) {
        if (source != null && destination != null) destination.copyFrom(source);
    }

    @Override
    public ResolvedTexture resolveEntityTexture(Entity entity, RenderType type) {
        if (entity instanceof Painting painting) return resolvePainting(painting);
        if (entity instanceof ItemFrame frame) return resolveItemFrame(frame);
        return null;
    }

    private ResolvedTexture resolvePainting(Painting painting) {
        try {
            Holder<PaintingVariant> variantHolder = painting.getVariant();
            PaintingVariant variant = variantHolder.value();
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.PAINTINGS)
                    .getSprite(variant.assetId());
            if (sprite != null) {
                Identifier spriteName = sprite.contents() != null ? sprite.contents().name() : variant.assetId();
                spriteName = normalizePaintingSpriteName(spriteName);
                return new ResolvedTexture(spriteName,
                        sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                        true, sprite, sprite.atlasLocation());
            }

            Identifier assetId = variant.assetId();
            String path = assetId.getPath();
            if (!path.startsWith("textures/")) {
                path = path.startsWith("painting/") ? "textures/" + path : "textures/painting/" + path;
            }
            if (!path.endsWith(".png")) path += ".png";
            Identifier texture = Identifier.fromNamespaceAndPath(assetId.getNamespace(), path);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception exception) {
            VoxelBridgeLogger.warn(LogModule.ENTITY,
                    "Failed to resolve painting texture via platform helper: " + exception);
            return null;
        }
    }

    private ResolvedTexture resolveItemFrame(ItemFrame frame) {
        try {
            Identifier wood = Identifier.withDefaultNamespace("block/birch_planks");
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.BLOCKS)
                    .getSprite(wood);
            if (sprite != null) {
                Identifier spriteName = sprite.contents() != null ? sprite.contents().name() : wood;
                return new ResolvedTexture(spriteName,
                        sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                        true, sprite, sprite.atlasLocation());
            }
        } catch (Exception ignored) {
        }

        boolean glow = frame instanceof net.minecraft.world.entity.decoration.GlowItemFrame;
        String path = glow ? "textures/entity/glow_item_frame.png" : "textures/entity/item_frame.png";
        Identifier texture = Identifier.fromNamespaceAndPath("minecraft", path);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    private static Identifier normalizePaintingSpriteName(Identifier spriteName) {
        if (spriteName == null) return null;
        String path = spriteName.getPath();
        if (path.startsWith("textures/painting/") || path.startsWith("painting/")) return spriteName;
        return Identifier.fromNamespaceAndPath(spriteName.getNamespace(), "painting/" + path);
    }
}
