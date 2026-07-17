package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.export.exporter.resolve.TextureResolver;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.platform.render.RenderTypeTextureResolver;
import com.voxelbridge.util.debug.LogModule;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.WoodType;

/**
 * Resolves the actual texture used by a BlockEntity render. Some render types
 * only expose atlas placeholders (e.g. chest/sign atlases). This mapper uses
 * the block entity state to find the concrete texture so that exporting can
 * load it from the resource manager.
 */
public final class BlockEntityTextureResolver implements TextureResolver<BlockEntity> {

    public static final BlockEntityTextureResolver INSTANCE = new BlockEntityTextureResolver();

    private BlockEntityTextureResolver() {}

    private static final boolean IS_CHRISTMAS = isChristmasWindow();

    /**
     * Resolve the texture for a block entity render, falling back to the render
     * type's texture when no better match is available.
     */
    @Override
    public ResolvedTexture resolve(BlockEntity blockEntity, RenderType renderType) {
        Identifier base = RenderTypeTextureResolver.INSTANCE.resolve(renderType);

        if (isTextRenderType(renderType)) {
            base = normalizeTextTexture(base, renderType);
            return base != null ? resolveTextureWithAtlasDetection(base) : null;
        }

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
    private static ResolvedTexture resolveTextureWithAtlasDetection(Identifier texture) {
        // List of known atlas locations to check
        Identifier[] knownAtlases = {
            Sheets.CHEST_SHEET,
            Sheets.BED_SHEET,
            Sheets.SIGN_SHEET,
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/decorated_pot.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/shulker_boxes.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png"),
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/shield_patterns.png")
        };

        // Try each atlas to see if the texture is there
        for (Identifier atlas : knownAtlases) {
            try {
                var atlasGetter = ClientAccessHolder.get().getTextureAtlas(atlas);
                if (atlasGetter != null) {
                    var sprite = atlasGetter.apply(texture);
                    if (sprite != null && !isMissingSprite(sprite)) {
                        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Found texture " + texture +
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
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Texture not in any atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }

    /**
     * Check if a sprite is the "missing texture" placeholder.
     */
    private static boolean isMissingSprite(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return sprite.contents().name().toString().contains("missingno");
    }

    private static boolean isTextRenderType(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);
        return name.contains("text_")
            || name.contains("neoforge_text")
            || name.contains("font")
            || name.contains("glyph");
    }

    private static Identifier normalizeTextTexture(Identifier base, RenderType renderType) {
        // Try extracting concrete font page from RenderType string first.
        Identifier fromRenderType = extractFontTextureFromRenderType(renderType);
        if (fromRenderType != null) {
            return fromRenderType;
        }
        if (base == null) {
            return null;
        }
        String path = base.getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("font/")) {
            return base;
        }
        return base;
    }

    private static Identifier extractFontTextureFromRenderType(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        String raw = renderType.toString();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.toLowerCase(java.util.Locale.ROOT);

        java.util.regex.Matcher dyn = java.util.regex.Pattern
            .compile("([a-z0-9_.-]+:font/[a-z0-9_./-]+)")
            .matcher(s);
        if (dyn.find()) {
            try {
                return Identifier.parse(dyn.group(1));
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Entity-specific texture resolution for BlockEntities with special requirements.
     * This is only needed for entities where we need to extract variant-specific textures
     * (e.g., chest type, bed color, sign wood type).
     *
     * For most BlockEntities, the generic atlas detection in resolveTextureWithAtlasDetection
     * is sufficient.
     */
    private static ResolvedTexture resolveFromBlockEntity(BlockEntity blockEntity, Identifier current) {
        if (blockEntity == null) {
            return null;
        }

        // Chest family - need to determine variant (normal/trapped/christmas, left/right/single)
        if (blockEntity instanceof EnderChestBlockEntity) {
            Identifier tex = Identifier.fromNamespaceAndPath("minecraft", "entity/chest/ender");
            return resolveTextureInAtlas(Sheets.CHEST_SHEET, tex);
        }
        if (blockEntity instanceof ChestBlockEntity chest) {
            return resolveChestTexture(chest);
        }

        // Beds - need to determine color
        if (blockEntity instanceof BedBlockEntity bed) {
            DyeColor color = bed.getColor();
            Identifier tex = Identifier.fromNamespaceAndPath("minecraft", "entity/bed/" + color.getName());
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
            return resolveTextureInAtlas(Sheets.CHEST_SHEET, Identifier.fromNamespaceAndPath("minecraft", "entity/chest/ender"));
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

        return resolveTextureInAtlas(Sheets.CHEST_SHEET, Identifier.fromNamespaceAndPath("minecraft", "entity/chest/" + base + suffix));
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
        Identifier texture = Identifier.fromNamespaceAndPath(namespace, prefix + path);
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
    private static ResolvedTexture resolveTextureInAtlas(Identifier atlas, Identifier texture) {
        if (atlas == null || texture == null) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Null atlas or texture, using fallback");
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, atlas);
        }

        try {
            // Load the sprite from the atlas to get UV bounds
            var atlasGetter = ClientAccessHolder.get().getTextureAtlas(atlas);
            if (atlasGetter != null) {
                var atlasSprite = atlasGetter.apply(texture);
                if (atlasSprite != null && !isMissingSprite(atlasSprite)) {
                    com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Resolved sprite for " + texture + " in atlas " + atlas +
                        " UV: [" + atlasSprite.getU0() + "," + atlasSprite.getU1() + "] x [" + atlasSprite.getV0() + "," + atlasSprite.getV1() + "]");
                    return new ResolvedTexture(texture, atlasSprite.getU0(), atlasSprite.getU1(),
                        atlasSprite.getV0(), atlasSprite.getV1(), true, atlasSprite, atlas);
                }
            }
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Failed to resolve sprite: " + e.getMessage());
        }
        // Fallbacks
        if (texture != null && texture.getPath().startsWith("textures/atlas/")) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Atlas texture not resolved via sprite, using full atlas: " + texture);
            return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, true, null, texture);
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.TEXTURE_RESOLVE, "[BlockEntityTextureResolver] Texture not in atlas, treating as standalone: " + texture);
        return new ResolvedTexture(texture, 0f, 1f, 0f, 1f, false, null, null);
    }
}
