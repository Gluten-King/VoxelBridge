package com.voxelbridge.export.exporter.blockentity;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Resolves the actual texture used by a BlockEntity render. Some render types
 * only expose atlas placeholders (e.g. chest/sign atlases). This mapper uses
 * the block entity state to find the concrete texture so that exporting can
 * load it from the resource manager.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockEntityTextureResolver {

    private BlockEntityTextureResolver() {
    }

    private static final boolean IS_CHRISTMAS = isChristmasWindow();

    public record ResolvedTexture(ResourceLocation texture, float u0, float u1, float v0, float v1, boolean isAtlasTexture, TextureAtlasSprite sprite, ResourceLocation atlasLocation) {
    }

    /**
     * Resolve the texture for a block entity render, falling back to the render
     * type's texture when no better match is available.
     */
    public static ResolvedTexture resolve(BlockEntity blockEntity, RenderType renderType) {
        ResourceLocation base = RenderTypeTextureResolver.resolve(renderType);

        // First try entity-specific resolution (for known BlockEntity types)
        ResolvedTexture mapped = resolveFromBlockEntity(blockEntity, base);
        if (mapped != null) {
            return mapped;
        }

        // If we have a base texture, try to detect if it's an atlas
        if (base != null) {
            return resolveTextureWithAtlasDetection(base);
        }

        return null;
    }

    /**
     * Generic method to resolve a texture and detect if it's in an atlas.
     * This works for any BlockEntity without needing entity-specific code.
     */
    private static ResolvedTexture resolveTextureWithAtlasDetection(ResourceLocation texture) {
        // List of known atlas locations to check
        ResourceLocation[] knownAtlases = {
            Sheets.CHEST_SHEET,
            Sheets.BED_SHEET,
            Sheets.SIGN_SHEET,
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/decorated_pot.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/shulker_boxes.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/shield_patterns.png")
        };

        // Try each atlas to see if the texture is there
        for (ResourceLocation atlas : knownAtlases) {
            try {
                var atlasGetter = net.minecraft.client.Minecraft.getInstance().getTextureAtlas(atlas);
                if (atlasGetter != null) {
                    var sprite = atlasGetter.apply(texture);
                    if (sprite != null && !isMissingSprite(sprite)) {
                        com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Found texture " + texture +
                            " in atlas " + atlas);
                        return new ResolvedTexture(texture, sprite.getU0(), sprite.getU1(),
                            sprite.getV0(), sprite.getV1(), true, sprite, atlas);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Not found in any atlas - treat as standalone texture
        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Texture not in any atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    /**
     * Check if a sprite is the "missing texture" placeholder.
     */
    private static boolean isMissingSprite(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return sprite.contents().name().toString().contains("missingno");
    }

    /**
     * Entity-specific texture resolution for BlockEntities with special requirements.
     * This is only needed for entities where we need to extract variant-specific textures
     * (e.g., chest type, bed color, sign wood type).
     *
     * For most BlockEntities, the generic atlas detection in resolveTextureWithAtlasDetection
     * is sufficient.
     */
    private static ResolvedTexture resolveFromBlockEntity(BlockEntity blockEntity, ResourceLocation current) {
        if (blockEntity == null) {
            return null;
        }

        // Chest family - need to determine variant (normal/trapped/christmas, left/right/single)
        if (blockEntity instanceof EnderChestBlockEntity) {
            ResourceLocation tex = ResourceLocation.fromNamespaceAndPath("minecraft", "entity/chest/ender");
            return resolveTextureInAtlas(Sheets.CHEST_SHEET, tex);
        }
        if (blockEntity instanceof ChestBlockEntity chest) {
            return resolveChestTexture(chest);
        }

        // Beds - need to determine color
        if (blockEntity instanceof BedBlockEntity bed) {
            DyeColor color = bed.getColor();
            ResourceLocation tex = ResourceLocation.fromNamespaceAndPath("minecraft", "entity/bed/" + color.getName());
            return resolveTextureInAtlas(Sheets.BED_SHEET, tex);
        }

        // Signs - need to determine wood type
        if (blockEntity instanceof HangingSignBlockEntity hangingSign) {
            return resolveSignTexture(hangingSign.getBlockState().getBlock(), true);
        }
        if (blockEntity instanceof SignBlockEntity sign) {
            return resolveSignTexture(sign.getBlockState().getBlock(), false);
        }

        // For other BlockEntities (including DecoratedPot), the generic method will handle them
        return null;
    }

    private static ResolvedTexture resolveChestTexture(ChestBlockEntity chest) {
        Block block = chest.getBlockState().getBlock();

        // Ender chest already handled above, but keep a guard here in case the block entity leaks through.
        if (block instanceof EnderChestBlock) {
            return resolveTextureInAtlas(Sheets.CHEST_SHEET, ResourceLocation.fromNamespaceAndPath("minecraft", "entity/chest/ender"));
        }

        boolean isTrapped = block instanceof TrappedChestBlock;
        boolean isChristmas = IS_CHRISTMAS;

        ChestType type = chest.getBlockState().hasProperty(ChestBlock.TYPE)
            ? chest.getBlockState().getValue(ChestBlock.TYPE)
            : ChestType.SINGLE;

        String base = isChristmas ? "christmas" : (isTrapped ? "trapped" : "normal");
        String suffix = switch (type) {
            case LEFT -> "_left";
            case RIGHT -> "_right";
            default -> "";
        };

        return resolveTextureInAtlas(Sheets.CHEST_SHEET, ResourceLocation.fromNamespaceAndPath("minecraft", "entity/chest/" + base + suffix));
    }

    private static ResolvedTexture resolveSignTexture(Block block, boolean hanging) {
        WoodType woodType = extractWoodType(block);
        if (woodType == null) {
            return null;
        }

        ResourceLocation woodId = ResourceLocation.tryParse(woodType.name());
        String namespace = woodId != null ? woodId.getNamespace() : "minecraft";
        String path = woodId != null ? woodId.getPath() : woodType.name();

        String prefix = hanging ? "entity/signs/hanging/" : "entity/signs/";
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(namespace, prefix + path);
        // Both normal and hanging signs use the same SIGN_SHEET atlas
        return resolveTextureInAtlas(Sheets.SIGN_SHEET, texture);
    }

    private static WoodType extractWoodType(Block block) {
        if (block instanceof SignBlock sign) {
            return sign.type();
        }
        if (block instanceof WallSignBlock wallSign) {
            return wallSign.type();
        }
        if (block instanceof CeilingHangingSignBlock hangingSign) {
            return hangingSign.type();
        }
        if (block instanceof WallHangingSignBlock wallHangingSign) {
            return wallHangingSign.type();
        }
        return null;
    }

    private static boolean isChristmasWindow() {
        java.time.MonthDay today = java.time.MonthDay.now(java.time.ZoneOffset.UTC);
        java.time.MonthDay start = java.time.MonthDay.of(12, 24);
        java.time.MonthDay end = java.time.MonthDay.of(12, 26);
        return !today.isBefore(start) && !today.isAfter(end);
    }

    /**
     * Resolve a texture within a specific atlas.
     */
    private static ResolvedTexture resolveTextureInAtlas(ResourceLocation atlas, ResourceLocation texture) {
        if (atlas == null || texture == null) {
            com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Null atlas or texture, using fallback");
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, atlas);
        }

        try {
            // Load the sprite from the atlas to get UV bounds
            var atlasGetter = net.minecraft.client.Minecraft.getInstance().getTextureAtlas(atlas);
            if (atlasGetter != null) {
                var atlasSprite = atlasGetter.apply(texture);
                if (atlasSprite != null && !isMissingSprite(atlasSprite)) {
                    com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Resolved sprite for " + texture + " in atlas " + atlas +
                        " UV: [" + atlasSprite.getU0() + "," + atlasSprite.getU1() + "] x [" + atlasSprite.getV0() + "," + atlasSprite.getV1() + "]");
                    return new ResolvedTexture(texture, atlasSprite.getU0(), atlasSprite.getU1(),
                        atlasSprite.getV0(), atlasSprite.getV1(), true, atlasSprite, atlas);
                }
            }
        } catch (Exception e) {
            com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Failed to resolve sprite: " + e.getMessage());
        }
        // Fallbacks
        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.ExportLogger.log("[BlockEntityTextureResolver] Texture not in atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }
}
