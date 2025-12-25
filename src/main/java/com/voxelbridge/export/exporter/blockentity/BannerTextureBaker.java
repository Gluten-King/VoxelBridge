package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.export.texture.TextureLoader;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
final class BannerTextureBaker {
    private static final String OUTPUT_DIR = "entity_textures/banner";
    private static final ResourceLocation FLAG_ONLY_TEXTURE = ResourceLocation.withDefaultNamespace("entity/banner/base");
    private static final ResourceLocation BASE_WITH_POLE_TEXTURE = ResourceLocation.withDefaultNamespace("entity/banner_base");
    private static final float[] NO_TINT = new float[]{1.0f, 1.0f, 1.0f};

    private BannerTextureBaker() {
    }

    static BannerTextures bake(ExportContext ctx, BannerBlockEntity banner) {
        String key = BannerTextureBaker.buildKey(banner);
        String bakedPath = OUTPUT_DIR + "/" + BannerTextureBaker.safe(key) + ".png";
        BufferedImage bakedImage = ctx.getGeneratedEntityTextures().computeIfAbsent(key, k -> BannerTextureBaker.composeTexture(banner));
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
            ResourceLocation sprite = Sheets.getBannerMaterial((Holder)layer.pattern()).texture();
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

    private static BufferedImage composeTexture(BannerBlockEntity banner) {
        BufferedImage base = BannerTextureBaker.copy(BannerTextureBaker.loadSprite(BASE_WITH_POLE_TEXTURE));
        BufferedImage result = BannerTextureBaker.copy(base);
        BannerTextureBaker.applyTinted(result, FLAG_ONLY_TEXTURE, banner.getBaseColor());
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            BannerTextureBaker.applyTinted(result, Sheets.getBannerMaterial((Holder)layer.pattern()).texture(), layer.color());
        }
        return result;
    }

    private static void applyTinted(BufferedImage target, ResourceLocation sprite, DyeColor color) {
        BufferedImage texture = BannerTextureBaker.loadSprite(sprite);
        if (texture == null) {
            return;
        }

        // Resource packs may change grayscale size; scale to target before tinting/compositing.
        int targetW = target.getWidth();
        int targetH = target.getHeight();
        if (texture.getWidth() != targetW || texture.getHeight() != targetH) {
            texture = BannerTextureBaker.scaleTo(texture, targetW, targetH);
        }

        float[] mul = color != null ? TextureLoader.rgbMul(color.getTextureDiffuseColor()) : NO_TINT;
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
                int tinted = a << 24 | BannerTextureBaker.clampColor(r) << 16 | BannerTextureBaker.clampColor(g) << 8 | BannerTextureBaker.clampColor(b);
                int out = BannerTextureBaker.alphaBlend(target.getRGB(x, y), tinted);
                target.setRGB(x, y, out);
            }
        }
    }

    private static int clampColor(int c) {
        return Math.max(0, Math.min(255, c));
    }

    private static int alphaBlend(int dst, int src) {
        int srcA = src >>> 24 & 0xFF;
        int dstA = dst >>> 24 & 0xFF;
        int outA = srcA + dstA * (255 - srcA) / 255;
        if (outA == 0) {
            return 0;
        }
        int srcR = src >>> 16 & 0xFF;
        int srcG = src >>> 8 & 0xFF;
        int srcB = src & 0xFF;
        int dstR = dst >>> 16 & 0xFF;
        int dstG = dst >>> 8 & 0xFF;
        int dstB = dst & 0xFF;
        int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;
        int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
        int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;
        return outA << 24 | outR << 16 | outG << 8 | outB;
    }

    private static BufferedImage loadSprite(ResourceLocation sprite) {
        ResourceLocation png = TextureLoader.spriteKeyToTexturePNG(sprite.toString());
        return TextureLoader.readTexture(png);
    }

    private static BufferedImage copy(BufferedImage src) {
        if (src == null) {
            return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        }
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static String buildKey(BannerBlockEntity banner) {
        StringBuilder sb = new StringBuilder("base:");
        sb.append(banner.getBaseColor().getSerializedName());
        int index = 0;
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            ResourceLocation id = layer.pattern().unwrapKey().map(ResourceKey::location).orElseGet(() -> ((BannerPattern)layer.pattern().value()).assetId());
            String colorName = layer.color() != null ? layer.color().getSerializedName() : "none";
            sb.append("__").append(index++).append(":").append(id).append("@").append(colorName);
        }
        return sb.toString();
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
        if (sb.length() == 0) {
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
