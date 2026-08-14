package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.core.util.color.ColorUtil;
import com.voxelbridge.core.util.image.ImageUtil;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
final class BannerTextureBaker {
    private static final ResourceLocation FLAG_ONLY_TEXTURE = ResourceLocation.withDefaultNamespace("entity/banner/base");
    private static final ResourceLocation BASE_WITH_POLE_TEXTURE = ResourceLocation.withDefaultNamespace("entity/banner_base");
    private static final float[] NO_TINT = new float[]{1.0f, 1.0f, 1.0f};

    private BannerTextureBaker() {
    }

    static BannerTextures bake(ExportContext ctx, BannerBlockEntity banner) {
        String key = BannerTextureBaker.buildKey(banner);
        String bakedPath = resolveOutputDir(ctx) + "/" + BannerTextureBaker.safe(key) + ".png";
        BufferedImage bakedImage = ctx.getGeneratedEntityTextures().computeIfAbsent(key, k -> BannerTextureBaker.composeTexture(ctx, banner));
        EntityTextureManager.TextureHandle bakedHandle = EntityTextureManager.registerGenerated(ctx, key, bakedPath, bakedImage);

        BannerTextureOverrides overrides = new BannerTextureOverrides();
        overrides.setBakedHandle(bakedHandle);
        ResourceLocation bannerBaseTexture = ModelBakery.BANNER_BASE.texture();
        ResourceLocation sheetsBannerBaseTexture = Sheets.BANNER_BASE.texture();
        overrides.map(bannerBaseTexture, bakedHandle);
        overrides.map(BASE_WITH_POLE_TEXTURE, bakedHandle);
        overrides.mapAndSkip(sheetsBannerBaseTexture, bakedHandle);
        ResourceLocation altBase1 = ResourceLocation.fromNamespaceAndPath("minecraft", "entity/banner_base");
        ResourceLocation altBase2 = ResourceLocation.fromNamespaceAndPath("minecraft", "entity/banner/base");
        overrides.map(altBase1, bakedHandle);
        overrides.mapAndSkip(altBase2, bakedHandle);
        overrides.skipSprite(FLAG_ONLY_TEXTURE);
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            ResourceLocation sprite = Sheets.getBannerMaterial(layer.pattern()).texture();
            overrides.mapAndSkip(sprite, bakedHandle);
            String patternPath = sprite.getPath();
            if (patternPath.contains("/")) {
                String altPath = patternPath.replace("entity/banner/", "entity/banner_");
                ResourceLocation altSprite = ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(), altPath);
                overrides.mapAndSkip(altSprite, bakedHandle);
            }
        }
        return new BannerTextures(bakedHandle, overrides);
    }

    private static BufferedImage composeTexture(ExportContext ctx, BannerBlockEntity banner) {
        BufferedImage base = ImageUtil.copyOrBlank(BannerTextureBaker.loadSprite(ctx, BASE_WITH_POLE_TEXTURE), 64, 64);
        BufferedImage result = ImageUtil.copy(base);
        BannerTextureBaker.applyTinted(ctx, result, FLAG_ONLY_TEXTURE, banner.getBaseColor());
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            BannerTextureBaker.applyTinted(ctx, result, Sheets.getBannerMaterial(layer.pattern()).texture(), layer.color());
        }
        return result;
    }

    private static void applyTinted(ExportContext ctx, BufferedImage target, ResourceLocation sprite, DyeColor color) {
        BufferedImage texture = BannerTextureBaker.loadSprite(ctx, sprite);
        if (texture == null) {
            return;
        }

        // Resource packs may change grayscale size; scale to target before tinting/compositing.
        int targetW = target.getWidth();
        int targetH = target.getHeight();
        if (texture.getWidth() != targetW || texture.getHeight() != targetH) {
            texture = ImageUtil.scaleNearest(texture, targetW, targetH);
        }

        float[] mul = color != null ? ColorUtil.rgbMul(color.getTextureDiffuseColor()) : NO_TINT;
        int w = targetW;
        int h = targetH;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int argb = texture.getRGB(x, y);
                int a = argb >>> 24 & 0xFF;
                if (a == 0) continue;
                int r = (int)((float)(argb >>> 16 & 0xFF) * mul[0]);
                int g = (int)((float)(argb >>> 8 & 0xFF) * mul[1]);
                int b = (int)((float)(argb & 0xFF) * mul[2]);
                int tinted = a << 24 | ImageUtil.clampChannel(r) << 16 | ImageUtil.clampChannel(g) << 8 | ImageUtil.clampChannel(b);
                int out = ImageUtil.alphaBlend(target.getRGB(x, y), tinted);
                target.setRGB(x, y, out);
            }
        }
    }

    private static BufferedImage loadSprite(ExportContext ctx, ResourceLocation sprite) {
        String resourceKey = ctx.resourceKeyForSprite(sprite.toString());
        return ctx.readTexture(resourceKey);
    }

    private static String buildKey(BannerBlockEntity banner) {
        StringBuilder sb = new StringBuilder("base:");
        sb.append(banner.getBaseColor().getSerializedName());
        int index = 0;
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            ResourceLocation id = layer.pattern().unwrapKey().map(ResourceKey::location).orElseGet(() -> layer.pattern().value().assetId());
            String colorName = layer.color() != null ? layer.color().getSerializedName() : "none";
            sb.append("__").append(index++).append(":").append(id).append("@").append(colorName);
        }
        return sb.toString();
    }

    private static String resolveOutputDir(ExportContext ctx) {
        return ctx.textureOptions().atlasMode()
            == com.voxelbridge.export.texture.ExportOptions.AtlasMode.INDIVIDUAL
            ? "textures/individual"
            : "entity_textures/banner";
    }

    private static String safe(String s) {
        String sanitized = BannerTextureBaker.sanitize(s);
        if (sanitized.length() <= 80) {
            return sanitized;
        }
        return sanitized.substring(0, Math.min(40, sanitized.length())) + "_" + BannerTextureBaker.sha1Hex(s).substring(0, 16);
    }

    private static String sanitize(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); ++i) {
            char c = Character.toLowerCase(input.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.isEmpty()) {
            sb.append("banner");
        }
        return sb.toString();
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit(b >> 4 & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static final class BannerTextureOverrides implements TextureOverrideMap {
        private final Map<ResourceLocation, EntityTextureManager.TextureHandle> overrides = new HashMap<>();
        private final Set<ResourceLocation> skipSprites = new HashSet<>();
        private EntityTextureManager.TextureHandle bakedHandle;

        void setBakedHandle(EntityTextureManager.TextureHandle handle) {
            this.bakedHandle = handle;
        }

        void map(ResourceLocation sprite, EntityTextureManager.TextureHandle handle) {
            this.overrides.put(sprite, handle);
        }

        void mapAndSkip(ResourceLocation sprite, EntityTextureManager.TextureHandle handle) {
            this.map(sprite, handle);
            this.skipSprite(sprite);
        }

        void skipSprite(ResourceLocation sprite) {
            this.skipSprites.add(sprite);
        }

        @Override
        public EntityTextureManager.TextureHandle resolve(ResourceLocation spriteName) {
            EntityTextureManager.TextureHandle mapped = this.overrides.get(spriteName);
            if (mapped != null) {
                return mapped;
            }
            return this.bakedHandle;
        }

        @Override
        public boolean skipQuad(ResourceLocation spriteName, float[] localU, float[] localV) {
            return spriteName != null && this.skipSprites.contains(spriteName);
        }
    }

    record BannerTextures(EntityTextureManager.TextureHandle bakedHandle, TextureOverrideMap overrides) {
    }
}
