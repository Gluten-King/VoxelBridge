package com.voxelbridge.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.compat.FabricAtlasAccess;
import com.voxelbridge.compat.FabricSpriteAccess;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
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
import java.util.Optional;

/**
 * Fabric implementation of PlatformTextureHelper.
 */
public class FabricPlatformTextureHelper implements PlatformTextureHelper {

    @Override
    public int getPixelRgba(NativeImage img, int x, int y) {
        if (img == null) {
            return 0;
        }
        int abgr = img.getPixel(x, y);
        return swapRedBlue(abgr);
    }

    @Override
    public NativeImage getOriginalImage(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return null;
        }
        return FabricSpriteAccess.getOriginalImage(sprite);
    }

    @Override
    public Collection<TextureAtlasSprite> getAllSprites(TextureAtlas atlas) {
        if (atlas == null) {
            return Collections.emptyList();
        }
        return FabricAtlasAccess.getAllSprites(atlas);
    }

    @Override
    public Optional<NativeImage> readTexture(Identifier location) {
        return FabricDynamicTextureReader.INSTANCE.readTexture(location);
    }

    @Override
    public void copyNativeImage(NativeImage src, NativeImage dst) {
        if (src != null && dst != null) {
            dst.copyFrom(src);
        }
    }

    private static int swapRedBlue(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public ResolvedTexture resolveEntityTexture(Entity entity, RenderType type) {
        if (entity instanceof Painting painting) {
            return resolvePainting(painting);
        }
        if (entity instanceof ItemFrame frame) {
            return resolveItemFrame(frame);
        }
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
                return new ResolvedTexture(
                    spriteName,
                    sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                    true,
                    sprite,
                    sprite.atlasLocation());
            }

            // Fallback to file path
            Identifier assetId = variant.assetId();
            String path = assetId.getPath();
            if (!path.startsWith("textures/")) {
                if (path.startsWith("painting/")) {
                    path = "textures/" + path;
                } else {
                    path = "textures/painting/" + path;
                }
            }
            if (!path.endsWith(".png")) {
                path = path + ".png";
            }
            Identifier textureLoc = Identifier.fromNamespaceAndPath(assetId.getNamespace(), path);
            return new ResolvedTexture(textureLoc, 0f, 1f, 0f, 1f, false, null, null);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ENTITY, "Failed to resolve painting texture via platform helper: " + e);
            return null;
        }
    }

    private ResolvedTexture resolveItemFrame(ItemFrame frame) {
        try {
            Identifier woodLoc = Identifier.withDefaultNamespace("block/birch_planks");
            TextureAtlasSprite sprite = Minecraft.getInstance()
                .getAtlasManager()
                .getAtlasOrThrow(AtlasIds.BLOCKS)
                .getSprite(woodLoc);

            if (sprite != null) {
                Identifier spriteName = sprite.contents() != null ? sprite.contents().name() : woodLoc;
                return new ResolvedTexture(
                    spriteName,
                    sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
                    true,
                    sprite,
                    sprite.atlasLocation());
            }
        } catch (Exception ignored) {
        }

        boolean isGlow = frame instanceof net.minecraft.world.entity.decoration.GlowItemFrame;
        String path = isGlow ? "textures/entity/glow_item_frame.png" : "textures/entity/item_frame.png";
        Identifier loc = Identifier.fromNamespaceAndPath("minecraft", path);
        return new ResolvedTexture(loc, 0f, 1f, 0f, 1f, false, null, null);
    }

    private static Identifier normalizePaintingSpriteName(Identifier spriteName) {
        if (spriteName == null) {
            return null;
        }
        String path = spriteName.getPath();
        if (path.startsWith("textures/painting/") || path.startsWith("painting/")) {
            return spriteName;
        }
        return Identifier.fromNamespaceAndPath(spriteName.getNamespace(), "painting/" + path);
    }
}
