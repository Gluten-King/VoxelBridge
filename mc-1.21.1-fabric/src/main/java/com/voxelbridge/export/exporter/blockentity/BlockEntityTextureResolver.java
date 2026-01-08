package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderLayerTextureResolver;
import com.voxelbridge.util.debug.LogModule;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.HangingSignBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.WallHangingSignBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BedBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.WoodType;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;

/**
 * Resolves the actual texture used by a BlockEntity render.
 */
public final class BlockEntityTextureResolver implements TextureResolver<BlockEntity> {

    public static final BlockEntityTextureResolver INSTANCE = new BlockEntityTextureResolver();

    private BlockEntityTextureResolver() {}

    private static final boolean IS_CHRISTMAS = isChristmasWindow();

    @Override
    public ResolvedTexture resolve(BlockEntity blockEntity, RenderLayer renderType) {
        Identifier base = RenderLayerTextureResolver.INSTANCE.resolve(renderType);

        if (isTextRenderType(renderType)) {
            return base != null ? resolveTextureWithAtlasDetection(base) : null;
        }

        ResolvedTexture mapped = resolveFromBlockEntity(blockEntity, base);
        if (mapped != null) {
            return mapped;
        }

        if (base != null) {
            return resolveTextureWithAtlasDetection(base);
        }

        return null;
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
                        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                            "[BlockEntityTextureResolver] Found texture " + texture + " in atlas " + atlas);
                        return new ResolvedTexture(texture, sprite.getMinU(), sprite.getMaxU(),
                            sprite.getMinV(), sprite.getMaxV(), true, sprite, atlas);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                "[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
            "[BlockEntityTextureResolver] Texture not in any atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    private static boolean isMissingSprite(Sprite sprite) {
        return sprite.getContents().getId().toString().contains("missingno");
    }

    private static boolean isTextRenderType(RenderLayer renderType) {
        if (renderType == null) {
            return false;
        }
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);
        return name.contains("text_")
            || name.contains("font")
            || name.contains("glyph");
    }

    private static ResolvedTexture resolveFromBlockEntity(BlockEntity blockEntity, Identifier current) {
        if (blockEntity == null) {
            return null;
        }

        if (blockEntity instanceof EnderChestBlockEntity) {
            Identifier tex = Identifier.of("minecraft", "entity/chest/ender");
            return resolveTextureInAtlas(TexturedRenderLayers.CHEST_ATLAS_TEXTURE, tex);
        }
        if (blockEntity instanceof ChestBlockEntity chest) {
            return resolveChestTexture(chest);
        }

        if (blockEntity instanceof BedBlockEntity bed) {
            DyeColor color = bed.getColor();
            Identifier tex = Identifier.of("minecraft", "entity/bed/" + color.getName());
            return resolveTextureInAtlas(TexturedRenderLayers.BEDS_ATLAS_TEXTURE, tex);
        }

        if (blockEntity instanceof HangingSignBlockEntity hangingSign) {
            return resolveSignTexture(hangingSign.getCachedState().getBlock(), true);
        }
        if (blockEntity instanceof SignBlockEntity sign) {
            return resolveSignTexture(sign.getCachedState().getBlock(), false);
        }

        return null;
    }

    private static ResolvedTexture resolveChestTexture(ChestBlockEntity chest) {
        Block block = chest.getCachedState().getBlock();

        if (block instanceof EnderChestBlock) {
            return resolveTextureInAtlas(TexturedRenderLayers.CHEST_ATLAS_TEXTURE,
                Identifier.of("minecraft", "entity/chest/ender"));
        }

        boolean isTrapped = block instanceof TrappedChestBlock;
        boolean isChristmas = IS_CHRISTMAS;

        ChestType type = chest.getCachedState().contains(ChestBlock.CHEST_TYPE)
            ? chest.getCachedState().get(ChestBlock.CHEST_TYPE)
            : ChestType.SINGLE;

        String base = isChristmas ? "christmas" : (isTrapped ? "trapped" : "normal");
        String suffix = switch (type) {
            case LEFT -> "_left";
            case RIGHT -> "_right";
            default -> "";
        };

        return resolveTextureInAtlas(TexturedRenderLayers.CHEST_ATLAS_TEXTURE,
            Identifier.of("minecraft", "entity/chest/" + base + suffix));
    }

    private static ResolvedTexture resolveSignTexture(Block block, boolean hanging) {
        WoodType woodType = extractWoodType(block);
        if (woodType == null) {
            return null;
        }

        Identifier woodId = Identifier.tryParse(woodType.name());
        String namespace = woodId != null ? woodId.getNamespace() : "minecraft";
        String path = woodId != null ? woodId.getPath() : woodType.name();

        String prefix = hanging ? "entity/signs/hanging/" : "entity/signs/";
        Identifier texture = Identifier.of(namespace, prefix + path);
        return resolveTextureInAtlas(TexturedRenderLayers.SIGNS_ATLAS_TEXTURE, texture);
    }

    private static WoodType extractWoodType(Block block) {
        if (block instanceof SignBlock sign) {
            return sign.getWoodType();
        }
        if (block instanceof WallSignBlock wallSign) {
            return wallSign.getWoodType();
        }
        if (block instanceof HangingSignBlock hangingSign) {
            return hangingSign.getWoodType();
        }
        if (block instanceof WallHangingSignBlock wallHangingSign) {
            return wallHangingSign.getWoodType();
        }
        return null;
    }

    private static boolean isChristmasWindow() {
        java.time.MonthDay today = java.time.MonthDay.now(java.time.ZoneOffset.UTC);
        java.time.MonthDay start = java.time.MonthDay.of(12, 24);
        java.time.MonthDay end = java.time.MonthDay.of(12, 26);
        return !today.isBefore(start) && !today.isAfter(end);
    }

    private static ResolvedTexture resolveTextureInAtlas(Identifier atlas, Identifier texture) {
        if (atlas == null || texture == null) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                "[BlockEntityTextureResolver] Null atlas or texture, using fallback");
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, atlas);
        }

        try {
            var atlasGetter = ClientAccessHolder.get().getTextureAtlas(atlas);
            if (atlasGetter != null) {
                Sprite atlasSprite = atlasGetter.apply(texture);
                if (atlasSprite != null && !isMissingSprite(atlasSprite)) {
                    com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                        "[BlockEntityTextureResolver] Resolved sprite for " + texture + " in atlas " + atlas +
                            " UV: [" + atlasSprite.getMinU() + "," + atlasSprite.getMaxU() +
                            "] x [" + atlasSprite.getMinV() + "," + atlasSprite.getMaxV() + "]");
                    return new ResolvedTexture(texture, atlasSprite.getMinU(), atlasSprite.getMaxU(),
                        atlasSprite.getMinV(), atlasSprite.getMaxV(), true, atlasSprite, atlas);
                }
            }
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                "[BlockEntityTextureResolver] Failed to resolve sprite: " + e.getMessage());
        }
        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
                "[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE,
            "[BlockEntityTextureResolver] Texture not in atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }
}
