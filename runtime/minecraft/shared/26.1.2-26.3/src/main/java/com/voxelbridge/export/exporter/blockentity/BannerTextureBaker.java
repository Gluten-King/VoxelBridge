package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.core.util.color.ColorUtil;
import com.voxelbridge.core.util.image.ImageUtil;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
final class BannerTextureBaker {
    private static final Identifier FLAG_ONLY_TEXTURE = Identifier.withDefaultNamespace("entity/banner/base");
    private static final Identifier BASE_WITH_POLE_TEXTURE = Identifier.withDefaultNamespace("entity/banner_base");
    private static final Identifier BANNER_PATTERN_ATLAS =
        Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png");
    private static final float ATLAS_UV_EPSILON = 1.0e-5f;
    private static final boolean FORCE_ATLAS_UV_FALLBACK =
        Boolean.getBoolean("voxelbridge.banner.forceAtlasUvFallback");
    private static final float[] NO_TINT = new float[]{1.0f, 1.0f, 1.0f};

    private BannerTextureBaker() {
    }

    private static Identifier patternTexture(net.minecraft.core.Holder<BannerPattern> pattern) {
        Identifier asset = pattern.value().assetId();
        return Identifier.fromNamespaceAndPath(asset.getNamespace(), "entity/banner/" + asset.getPath());
    }

    static BannerTextures bake(ExportContext ctx, BannerBlockEntity banner) {
        String key = BannerTextureBaker.buildKey(banner);
        String bakedPath = resolveOutputDir(ctx) + "/" + BannerTextureBaker.safe(key) + ".png";
        BufferedImage bakedImage = ctx.getGeneratedEntityTextures().computeIfAbsent(key, k -> BannerTextureBaker.composeTexture(ctx, banner));
        if (VoxelBridgeLogger.isProbeEnabled()) {
            VoxelBridgeLogger.probe("banner-bake pos=" + banner.getBlockPos()
                + " patterns=" + banner.getPatterns().layers().size()
                + " image=" + bakedImage.getWidth() + "x" + bakedImage.getHeight()
                + " key=" + key);
        }
        EntityTextureManager.TextureHandle bakedHandle = EntityTextureManager.registerGenerated(ctx, key, bakedPath, bakedImage);

        BannerTextureOverrides overrides = new BannerTextureOverrides();
        overrides.setBakedHandle(bakedHandle);
        Identifier bannerBaseTexture = BASE_WITH_POLE_TEXTURE;
        Identifier sheetsBannerBaseTexture = FLAG_ONLY_TEXTURE;
        overrides.map(bannerBaseTexture, bakedHandle);
        overrides.map(BASE_WITH_POLE_TEXTURE, bakedHandle);
        overrides.mapAndSkip(sheetsBannerBaseTexture, bakedHandle);
        Identifier altBase1 = Identifier.fromNamespaceAndPath("minecraft", "entity/banner_base");
        Identifier altBase2 = Identifier.fromNamespaceAndPath("minecraft", "entity/banner/base");
        overrides.map(altBase1, bakedHandle);
        overrides.mapAndSkip(altBase2, bakedHandle);
        overrides.skipSprite(FLAG_ONLY_TEXTURE);
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            Identifier sprite = patternTexture(layer.pattern());
            overrides.mapAndSkip(sprite, bakedHandle);
            String patternPath = sprite.getPath();
            if (patternPath.contains("/")) {
                String altPath = patternPath.replace("entity/banner/", "entity/banner_");
                Identifier altSprite = Identifier.fromNamespaceAndPath(sprite.getNamespace(), altPath);
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
            BannerTextureBaker.applyTinted(ctx, result, patternTexture(layer.pattern()), layer.color());
        }
        return result;
    }

    private static void applyTinted(ExportContext ctx, BufferedImage target, Identifier sprite, DyeColor color) {
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

    private static BufferedImage loadSprite(ExportContext ctx, Identifier sprite) {
        String resourceKey = ctx.resourceKeyForSprite(sprite.toString());
        return ctx.readTexture(resourceKey);
    }

    private static String buildKey(BannerBlockEntity banner) {
        StringBuilder sb = new StringBuilder("base:");
        sb.append(banner.getBaseColor().getSerializedName());
        int index = 0;
        for (BannerPatternLayers.Layer layer : banner.getPatterns().layers()) {
            Identifier id = layer.pattern().unwrapKey().map(ResourceKey::identifier).orElseGet(() -> layer.pattern().value().assetId());
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
        private final Map<Identifier, EntityTextureManager.TextureHandle> overrides = new HashMap<>();
        private final Set<Identifier> skipSprites = new HashSet<>();
        private final Set<Identifier> probedSprites = new HashSet<>();
        private volatile List<UvBounds> skipUvBounds;
        private EntityTextureManager.TextureHandle bakedHandle;

        void setBakedHandle(EntityTextureManager.TextureHandle handle) {
            this.bakedHandle = handle;
        }

        void map(Identifier sprite, EntityTextureManager.TextureHandle handle) {
            this.overrides.put(sprite, handle);
        }

        void mapAndSkip(Identifier sprite, EntityTextureManager.TextureHandle handle) {
            this.map(sprite, handle);
            this.skipSprite(sprite);
        }

        void skipSprite(Identifier sprite) {
            this.skipSprites.add(sprite);
        }

        @Override
        public EntityTextureManager.TextureHandle resolve(Identifier spriteName) {
            EntityTextureManager.TextureHandle mapped = this.overrides.get(spriteName);
            if (mapped != null) {
                return mapped;
            }
            return this.bakedHandle;
        }

        @Override
        public boolean skipQuad(Identifier spriteName, float[] localU, float[] localV) {
            boolean skip = !FORCE_ATLAS_UV_FALLBACK
                && spriteName != null
                && this.skipSprites.contains(spriteName);
            String reason = skip ? "sprite-id" : "none";
            if (!skip && matchesSkippedAtlasRegion(localU, localV)) {
                skip = true;
                reason = "atlas-uv-fallback";
            }
            if (VoxelBridgeLogger.isProbeEnabled() && this.probedSprites.add(spriteName)) {
                VoxelBridgeLogger.probe("banner-layer sprite=" + spriteName
                    + " action=" + (skip ? "skip-pattern-layer" : "keep-geometry")
                    + " reason=" + reason
                    + " uv=" + uvRange(localU, localV));
            }
            return skip;
        }

        /**
         * Production renderers can expose only the banner atlas binding rather
         * than the concrete sprite. Match the raw atlas UVs against the exact
         * known pattern sprites so baked banner layers are still discarded.
         */
        private boolean matchesSkippedAtlasRegion(float[] u, float[] v) {
            if (u == null || v == null || u.length == 0 || v.length == 0) {
                return false;
            }
            for (UvBounds bounds : getSkipUvBounds()) {
                if (bounds.contains(u, v)) {
                    return true;
                }
            }
            return false;
        }

        private List<UvBounds> getSkipUvBounds() {
            List<UvBounds> current = this.skipUvBounds;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = this.skipUvBounds;
                if (current != null) {
                    return current;
                }
                List<UvBounds> resolved = new ArrayList<>();
                try {
                    var atlas = ClientAccessHolder.get().getTextureAtlas(BANNER_PATTERN_ATLAS);
                    if (atlas != null) {
                        for (Identifier spriteName : this.skipSprites) {
                            var sprite = atlas.apply(spriteName);
                            if (sprite == null || sprite.contents().name().toString().contains("missingno")) {
                                continue;
                            }
                            resolved.add(new UvBounds(
                                sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()
                            ));
                        }
                    }
                } catch (Exception e) {
                    if (VoxelBridgeLogger.isProbeEnabled()) {
                        VoxelBridgeLogger.probe("banner-atlas-uv-fallback unavailable: " + e.getMessage());
                    }
                }
                current = List.copyOf(resolved);
                this.skipUvBounds = current;
                if (VoxelBridgeLogger.isProbeEnabled()) {
                    VoxelBridgeLogger.probe("banner-atlas-uv-fallback regions=" + current.size());
                }
                return current;
            }
        }

        private static String uvRange(float[] u, float[] v) {
            if (u == null || v == null || u.length == 0 || v.length == 0) {
                return "unavailable";
            }
            float minU = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            for (float value : u) {
                minU = Math.min(minU, value);
                maxU = Math.max(maxU, value);
            }
            for (float value : v) {
                minV = Math.min(minV, value);
                maxV = Math.max(maxV, value);
            }
            return String.format(java.util.Locale.ROOT, "[%.6f..%.6f,%.6f..%.6f]",
                minU, maxU, minV, maxV);
        }

        private record UvBounds(float u0, float u1, float v0, float v1) {
            boolean contains(float[] u, float[] v) {
                for (float value : u) {
                    if (value < u0 - ATLAS_UV_EPSILON || value > u1 + ATLAS_UV_EPSILON) {
                        return false;
                    }
                }
                for (float value : v) {
                    if (value < v0 - ATLAS_UV_EPSILON || value > v1 + ATLAS_UV_EPSILON) {
                        return false;
                    }
                }
                return true;
            }
        }
    }

    record BannerTextures(EntityTextureManager.TextureHandle bakedHandle, TextureOverrideMap overrides) {
    }
}
