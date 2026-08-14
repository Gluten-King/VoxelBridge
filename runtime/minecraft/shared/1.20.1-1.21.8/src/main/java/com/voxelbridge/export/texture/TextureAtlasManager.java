package com.voxelbridge.export.texture;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.exporter.capture.CapturedQuadProcessor;
import com.voxelbridge.pipeline.texture.AnimationExportService;
import com.voxelbridge.pipeline.texture.AnimationDetectionService;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.texture.AtlasBuilder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * TextureAtlasManager handles atlas bookkeeping and generation.
 * Supports:
 * - INDIVIDUAL: single texture per sprite
 * - ATLAS: packed UDIM tiles using a simple shelf packer (size configurable)
 */
public final class TextureAtlasManager {
    private static final int MAX_TINT_SLOTS = 64 * 64;
    private static final int DEFAULT_TINT = 0xFFFFFF;

    private TextureAtlasManager() {}

    /**
     * Initializes reserved slots in the texture atlas.
     * Must be called at the beginning of export, before any texture registration.
     * Reserves slot 0 for the transparent texture (16x16 fully transparent).
     */
    public static void initializeReservedSlots(ExportContext ctx) {
        String transparentKey = CapturedQuadProcessor.TRANSPARENT_SPRITE_KEY;

        // Create 16x16 fully transparent image
        BufferedImage transparentImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                transparentImg.setRGB(x, y, 0x00000000);  // Fully transparent
            }
        }

        ctx.cacheSpriteImage(transparentKey, transparentImg);

        // Force occupy the first slot (index 0)
        ExportState.TintAtlas atlas = ctx.getOrCreateTintAtlas(transparentKey);
        atlas.tintToIndex.put(0xFFFFFF, 0);  // tint 0xFFFFFF ?slot 0
        atlas.indexToTint.put(0, 0xFFFFFF);
        atlas.nextIndex.set(1);  // Next available slot starts from 1

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[TextureAtlas] Reserved slot 0 for transparent texture (16x16)");
    }

    public static void registerTint(ExportContext ctx, String spriteKey, int tint) {
        registerTint(ctx.state(), spriteKey, tint);
    }

    public static void registerTint(ExportState state, String spriteKey, int tint) {
        ExportState.TintAtlas atlas = state.getOrCreateTintAtlas(spriteKey);
        int normalized = sanitizeTintValue(tint);
        atlas.tintToIndex.computeIfAbsent(normalized, key -> {
            int slot = reserveTintSlot(atlas, key);
            int totalSlots = Math.max(1, Math.min(MAX_TINT_SLOTS, atlas.nextIndex.get()));
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[Tint] sprite=%s tint=%06X slot=%d totalSlots=%d",
                        spriteKey, normalized, slot, totalSlots));
            }
            return slot;
        });
    }

    public static int getTintIndex(ExportState state, String spriteKey, int tint) {
        ExportState.TintAtlas atlas = state.getAtlasBook().computeIfAbsent(spriteKey,
                k -> new ExportState.TintAtlas());
        int normalized = sanitizeTintValue(tint);
        if (!atlas.tintToIndex.containsKey(normalized)) {
            registerTint(state, spriteKey, tint);
        }
        return atlas.tintToIndex.getOrDefault(normalized, 0);
    }

    public static boolean hasMultipleTints(ExportContext ctx, String spriteKey) {
        ExportState.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
        if (atlas == null) {
            return false;
        }
        return atlas.nextIndex.get() > 1;
    }

    /**
     * Verifies that the transparent texture has been initialized.
     * The transparent texture is pre-allocated in initializeReservedSlots().
     * This method is kept for backward compatibility and validation.
     */
    public static void registerTransparentTexture(ExportContext ctx, String spriteKey) {
        // Verify the transparent texture was initialized
        if (!spriteKey.equals(CapturedQuadProcessor.TRANSPARENT_SPRITE_KEY)) {
            throw new IllegalArgumentException("Only '" + CapturedQuadProcessor.TRANSPARENT_SPRITE_KEY + "' is supported as transparent texture key");
        }

        if (!ctx.getAtlasBook().containsKey(spriteKey)) {
            throw new IllegalStateException("Transparent texture not initialized! Call initializeReservedSlots() first.");
        }
    }

    public static void generateAllAtlases(ExportContext ctx, Path outDir) throws IOException {
        Path texRoot = outDir.resolve("textures");
        Files.createDirectories(texRoot);

        ExportOptions options = ctx.textureOptions();
        ExportOptions.AtlasMode atlasMode = options.atlasMode();
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[TextureAtlasManager] Generating atlases (mode=" + atlasMode + ")...");
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] ===== ATLAS GENERATION START =====");
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Atlas mode: %s", atlasMode));
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Total sprites in atlasBook: %d", ctx.getAtlasBook().size()));

        // Phase 0: Animation scanning limited to current export set (if enabled)
        java.util.Set<String> animationWhitelist = new java.util.HashSet<>();
        animationWhitelist.addAll(ctx.getAtlasBook().keySet());
        animationWhitelist.addAll(ctx.getCachedSpriteKeys());
        if (options.animationEnabled()) {
            VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] Starting animation scan (whitelisted to export set)...");

            VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Whitelist built: %d sprites", animationWhitelist.size()));
            VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] AtlasBook entries: " + ctx.getAtlasBook().size());
            VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] CachedSprite entries: " + ctx.getCachedSpriteKeys().size());

            int modCount = 0;
            for (String key : animationWhitelist) {
                if (!key.startsWith("minecraft:")) {
                    VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Whitelist mod sprite: " + key);
                    modCount++;
                }
            }
            VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Total mod sprites in whitelist: " + modCount);

            AnimatedTextureHelper.scanAllAnimations(ctx, animationWhitelist);

            // Phase 0.5: Export detected animations within whitelist
            exportAllDetectedAnimations(ctx, outDir, animationWhitelist);
        }

        // Auto-register any cached sprites (including CTM/PBR companions) that are not yet in atlasBook
        for (String cachedKey : ctx.getCachedSpriteKeys()) {
            if (!ctx.getAtlasBook().containsKey(cachedKey)) {
                registerTint(ctx, cachedKey, DEFAULT_TINT);
                if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                    VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] Auto-registered cached sprite: " + cachedKey);
                }
            }
        }

        // Ensure entity/blockentity/base sprites participate in atlas packing when atlas mode is enabled.
        if (atlasMode == ExportOptions.AtlasMode.ATLAS) {
            for (String spriteKey : ctx.getEntityTextures().keySet()) {
                if (!ctx.getAtlasBook().containsKey(spriteKey)) {
                    registerTint(ctx, spriteKey, DEFAULT_TINT);
                    if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] Registered entity sprite for atlas: " + spriteKey);
                    }
                }
            }
        }

        Map<String, ExportState.TintAtlas> blockEntries = new java.util.LinkedHashMap<>();
        ctx.getAtlasBook().forEach((key, atlas) -> {
            if (options.animationEnabled()) {
                ensureAnimationDetection(ctx, key);
            }
            boolean isInCache = ctx.getCachedSpriteImage(key) != null;
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Sprite registered: %s (inCache=%s, tints=%d)",
                    key, isInCache, atlas.nextIndex.get()));
            }
            // Only include base/albedo sprites; skip PBR companion keys
            boolean isAnimated = options.animationEnabled() && ctx.getTextureRepository().hasAnimation(key);
            if (!isPbrSprite(key) && !isAnimated) {
                blockEntries.put(key, atlas);
            }
        });

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Block sprites to process: %d", blockEntries.size()));

        Map<String, BufferedImage> baseImages = new LinkedHashMap<>();
        Map<String, BufferedImage> normalImages = new LinkedHashMap<>();
        Map<String, BufferedImage> specImages = new LinkedHashMap<>();
        for (String key : blockEntries.keySet()) {
            BufferedImage base = loadTextureForAtlas(ctx, key);
            if (base == null) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format(
                    "[AtlasGen][WARN] sprite=%s missing texture, using placeholder", key));
                base = AtlasBuilder.createMissingTexture();
            }
            baseImages.put(key, base);
            BufferedImage normal = ctx.getCachedSpriteImage(key + "_n");
            if (normal != null) {
                BufferedImage sanitized = PbrTextureHelper.sanitizeMissingNo(
                    normal, PbrTextureHelper.DEFAULT_NORMAL_COLOR, key + "_n");
                if (sanitized != null) {
                    normalImages.put(key, sanitized);
                    if (sanitized != normal) {
                        ctx.cacheSpriteImage(key + "_n", sanitized);
                    }
                }
            }
            BufferedImage spec = ctx.getCachedSpriteImage(key + "_s");
            if (spec != null) {
                BufferedImage sanitized = PbrTextureHelper.sanitizeMissingNo(
                    spec, PbrTextureHelper.DEFAULT_SPECULAR_COLOR, key + "_s");
                if (sanitized != null) {
                    specImages.put(key, sanitized);
                    if (sanitized != spec) {
                        ctx.cacheSpriteImage(key + "_s", sanitized);
                    }
                }
            }
        }

        if (atlasMode == ExportOptions.AtlasMode.INDIVIDUAL) {
            AtlasBuilder.generateIndividualTextures(
                options, ctx.state(), outDir, blockEntries, baseImages, normalImages, specImages, ctx.getMaterialPaths());
            return;
        }

        AtlasBuilder.generatePackedAtlas(
            options, ctx.state(), outDir, blockEntries, baseImages, normalImages, specImages, ctx.getMaterialPaths(),
            "textures/atlas", "atlas_", PbrTextureHelper.DEFAULT_NORMAL_COLOR,
            PbrTextureHelper.DEFAULT_SPECULAR_COLOR, ctx.session().options().workerThreads());
    }

    private static int sanitizeTintValue(int tint) {
        if (tint == -1) {
            return DEFAULT_TINT;
        }
        return tint & 0xFFFFFF;
    }

    private static int reserveTintSlot(ExportState.TintAtlas atlas, int tint) {
        int idx = atlas.nextIndex.get();
        if (idx < MAX_TINT_SLOTS) {
            atlas.indexToTint.put(idx, tint);
            atlas.nextIndex.incrementAndGet();
            return idx;
        }

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (var entry : atlas.indexToTint.entrySet()) {
            int dist = colorDistance(entry.getValue(), tint);
            if (dist < bestDistance) {
                bestDistance = dist;
                bestIndex = entry.getKey();
            }
        }
        atlas.indexToTint.put(bestIndex, tint);
        return bestIndex;
    }

    private static int colorDistance(int a, int b) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int dr = ar - br;
        int dg = ag - bg;
        int db = ab - bb;
        return dr * dr + dg * dg + db * db;
    }

    private static boolean isPbrSprite(String key) {
        return key != null && (key.endsWith("_n") || key.endsWith("_s"));
    }


    /** Public wrapper to export detected animations to a target directory. */
    public static void exportDetectedAnimations(ExportContext ctx, Path outDir, java.util.Set<String> whitelist) throws IOException {
        exportAllDetectedAnimations(ctx, outDir, whitelist);
    }

    /**
     * Export all animations detected by scanAllAnimations().
     * This ensures animations like campfire_fire are exported even if not in atlasBook.
     */
    private static void exportAllDetectedAnimations(ExportContext ctx, Path outDir, java.util.Set<String> whitelist) throws IOException {
        TextureRepository repo = ctx.getTextureRepository();
        VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Starting animation export...");
        VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Total animations in repository: " + repo.getAnimatedCache().size());
        AnimationExportService.Result result = AnimationExportService.export(
            repo, outDir, whitelist, ctx.getMaterialPaths(), spriteKey -> readOriginalMcmeta(ctx, spriteKey));
        VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
            "[Animation] ===== EXPORT COMPLETE: %d/%d animations exported =====",
            result.exportedAnimations(), result.discoveredAnimations()));
    }

    /**
     * Ensures we flag animations by scanning the full texture strip (not just first frame).
     */
    private static void ensureAnimationDetection(ExportContext ctx, String spriteKey) {
        if (ctx.getTextureRepository().hasAnimation(spriteKey)) {
            return;
        }
        String resourceKey = ctx.resourceKeyForSprite(spriteKey);
        if (resourceKey == null) {
            return;
        }
        AnimationDetectionService.ensure(
            ctx.session().runtime().textures(), ctx.getTextureRepository(), spriteKey,
            com.voxelbridge.pipeline.resource.ResourceIds.sanitize(resourceKey));
    }

    /**
     * Loads a texture for atlas generation (block + block entity).
     */
    private static BufferedImage loadTextureForAtlas(ExportContext ctx, String spriteKey) {
        BufferedImage cached = ctx.getCachedSpriteImage(spriteKey);
        if (cached != null) {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][CACHE HIT] Loaded %s from cache (%dx%d)",
                    spriteKey, cached.getWidth(), cached.getHeight()));
            }
            return cached;
        }

        String resourceKey = ctx.resourceKeyForSprite(spriteKey);
        if (resourceKey == null) {
            return null;
        }
        cached = ctx.getTextureRepository().get(resourceKey);
        if (cached != null) {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][CACHE HIT] Loaded %s from cache (%dx%d)",
                    spriteKey, cached.getWidth(), cached.getHeight()));
            }
            return cached;
        }

        if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
            VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][CACHE MISS] %s not in cache, trying disk", spriteKey));
        }
        BufferedImage diskImage = ctx.readTexture(resourceKey, ctx.textureOptions().animationEnabled());
        if (diskImage != null) {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][DISK HIT] Loaded %s from disk (%dx%d)",
                    spriteKey, diskImage.getWidth(), diskImage.getHeight()));
            }
            ctx.getTextureRepository().put(resourceKey, spriteKey, diskImage);
        } else {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][DISK MISS] Failed to load %s from disk", spriteKey));
            }
        }
        return diskImage;
    }

    private static String readOriginalMcmeta(ExportContext ctx, String spriteKey) {
        try {
            String pngKey = ctx.resourceKeyForSprite(spriteKey);
            if (pngKey == null) {
                return null;
            }
            String mcmetaKey = pngKey + ".mcmeta";
            byte[] bytes = ctx.readResource(mcmetaKey);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, String.format("[Animation][WARN] Failed to copy mcmeta for %s: %s", spriteKey, e.getMessage()));
        }
        return null;
    }
}
