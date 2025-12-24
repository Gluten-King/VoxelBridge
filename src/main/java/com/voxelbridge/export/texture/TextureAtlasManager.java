package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.config.ExportRuntimeConfig.AtlasMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportContext.BlockEntityAtlasPlacement;
import com.voxelbridge.export.ExportContext.TexturePlacement;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * TextureAtlasManager handles atlas bookkeeping and generation.
 * Supports:
 * - INDIVIDUAL: single texture per sprite
 * - ATLAS: packed UDIM tiles using a simple shelf packer (size configurable)
 */
@OnlyIn(Dist.CLIENT)
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
        String transparentKey = "voxelbridge:transparent";

        // Create 16x16 fully transparent image
        BufferedImage transparentImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                transparentImg.setRGB(x, y, 0x00000000);  // Fully transparent
            }
        }

        ctx.cacheSpriteImage(transparentKey, transparentImg);

        // Force occupy the first slot (index 0)
        ExportContext.TintAtlas atlas = ctx.getOrCreateTintAtlas(transparentKey);
        atlas.tintToIndex.put(0xFFFFFF, 0);  // tint 0xFFFFFF ?slot 0
        atlas.indexToTint.put(0, 0xFFFFFF);
        atlas.nextIndex.set(1);  // Next available slot starts from 1

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[TextureAtlas] Reserved slot 0 for transparent texture (16x16)");
    }

    public static void registerTint(ExportContext ctx, String spriteKey, int tint) {
        ExportContext.TintAtlas atlas = ctx.getOrCreateTintAtlas(spriteKey);
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

    public static int getTintIndex(ExportContext ctx, String spriteKey, int tint) {
        ExportContext.TintAtlas atlas = ctx.getAtlasBook().computeIfAbsent(spriteKey,
                k -> new ExportContext.TintAtlas());
        int normalized = sanitizeTintValue(tint);
        if (!atlas.tintToIndex.containsKey(normalized)) {
            registerTint(ctx, spriteKey, tint);
        }
        return atlas.tintToIndex.getOrDefault(normalized, 0);
    }

    public static boolean hasMultipleTints(ExportContext ctx, String spriteKey) {
        ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
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
        if (!spriteKey.equals("voxelbridge:transparent")) {
            throw new IllegalArgumentException("Only 'voxelbridge:transparent' is supported as transparent texture key");
        }

        if (!ctx.getAtlasBook().containsKey(spriteKey)) {
            throw new IllegalStateException("Transparent texture not initialized! Call initializeReservedSlots() first.");
        }
    }

    public static void generateAllAtlases(ExportContext ctx, Path outDir) throws IOException {
        Path texRoot = outDir.resolve("textures");
        Files.createDirectories(texRoot);

        AtlasMode atlasMode = ExportRuntimeConfig.getAtlasMode();
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[TextureAtlasManager] Generating atlases (mode=" + atlasMode + ")...");
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] ===== ATLAS GENERATION START =====");
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Atlas mode: %s", atlasMode));
        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Total sprites in atlasBook: %d", ctx.getAtlasBook().size()));

        // Phase 0: Animation scanning limited to current export set (if enabled)
        java.util.Set<String> animationWhitelist = new java.util.HashSet<>();
        animationWhitelist.addAll(ctx.getAtlasBook().keySet());
        animationWhitelist.addAll(ctx.getCachedSpriteKeys());
        if (ExportRuntimeConfig.isAnimationEnabled()) {
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
        if (atlasMode == AtlasMode.ATLAS) {
            for (String spriteKey : ctx.getEntityTextures().keySet()) {
                if (!ctx.getAtlasBook().containsKey(spriteKey)) {
                    registerTint(ctx, spriteKey, DEFAULT_TINT);
                    if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, "[AtlasGen] Registered entity sprite for atlas: " + spriteKey);
                    }
                }
            }
        }

        Map<String, ExportContext.TintAtlas> blockEntries = new java.util.LinkedHashMap<>();
        ctx.getAtlasBook().forEach((key, atlas) -> {
            if (ExportRuntimeConfig.isAnimationEnabled()) {
                ensureAnimationDetection(ctx, key);
            }
            boolean isInCache = ctx.getCachedSpriteImage(key) != null;
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Sprite registered: %s (inCache=%s, tints=%d)",
                    key, isInCache, atlas.nextIndex.get()));
            }
            // Only include base/albedo sprites; skip PBR companion keys
            boolean isAnimated = ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(key);
            if (!isPbrSprite(key) && !isAnimated) {
                blockEntries.put(key, atlas);
            }
        });

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Block sprites to process: %d", blockEntries.size()));

        if (atlasMode == AtlasMode.INDIVIDUAL) {
            generateIndividualTextures(ctx, outDir, blockEntries, "textures/individual/");
            return;
        }

        generatePackedAtlas(ctx, outDir, blockEntries, "textures/atlas", "atlas_");
    }

    private static void generateIndividualTextures(ExportContext ctx,
                                                   Path outDir,
                                                   Map<String, ExportContext.TintAtlas> entries,
                                                   String subDir) throws IOException {
        if (entries.isEmpty()) {
            return;
        }

        long tIndividual = VoxelBridgeLogger.now();
        for (Map.Entry<String, ExportContext.TintAtlas> entry : entries.entrySet()) {
            String spriteKey = entry.getKey();
            ExportContext.TintAtlas atlas = entry.getValue();
            atlas.placements.clear();

            if (atlas.tintToIndex.isEmpty()) {
                registerTint(ctx, spriteKey, DEFAULT_TINT);
            }

            int tintSlots = Math.max(1, Math.min(MAX_TINT_SLOTS, atlas.nextIndex.get()));
            int[] tintBySlot = resolveTintSlots(atlas, tintSlots);

            // [FIX] Use updated loadTextureForAtlas that checks cache
            BufferedImage base = loadTextureForAtlas(ctx, spriteKey);

            if (base == null) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][WARN] sprite=%s missing texture, using placeholder", spriteKey));
                base = createMissingTexture();
            }

            BufferedImage outputImage = tintTile(base, tintBySlot[0]);
            String relativePath = subDir + safe(spriteKey) + ".png";
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            PngjWriter.write(outputImage, target);
            atlas.atlasFile = target;
            atlas.texW = outputImage.getWidth();
            atlas.texH = outputImage.getHeight();
            atlas.usesAtlas = false;
            atlas.cols = 1;
            atlas.placements.clear();
            ctx.getMaterialPaths().put(spriteKey, relativePath);

            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] sprite=%s mode=individual path=%s", spriteKey, relativePath));
            }

            // Dump PBR companions if present in cache for debugging/inspection
            BufferedImage normal = ctx.getCachedSpriteImage(spriteKey + "_n");
            if (normal != null) {
                String normalRel = subDir + safe(spriteKey) + "_n.png";
                Path normalTarget = outDir.resolve(normalRel);
                Files.createDirectories(normalTarget.getParent());
                PngjWriter.write(normal, normalTarget);
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] sprite=%s normal exported: %s", spriteKey, normalRel));
                ctx.getMaterialPaths().put(spriteKey + "_n", normalRel);
            }
            BufferedImage spec = ctx.getCachedSpriteImage(spriteKey + "_s");
            if (spec != null) {
                String specRel = subDir + safe(spriteKey) + "_s.png";
                Path specTarget = outDir.resolve(specRel);
                Files.createDirectories(specTarget.getParent());
                PngjWriter.write(spec, specTarget);
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] sprite=%s specular exported: %s", spriteKey, specRel));
                ctx.getMaterialPaths().put(spriteKey + "_s", specRel);
            }
        }
        VoxelBridgeLogger.duration("individual_texture_write", VoxelBridgeLogger.elapsedSince(tIndividual));
    }

    private record AtlasRequest(String spriteKey, int tintIndex, BufferedImage image) {}
    private record TintTask(String spriteKey, int tintIndex, int tint) {}

    private static void generatePackedAtlas(ExportContext ctx,
                                            Path outDir,
                                            Map<String, ExportContext.TintAtlas> entries,
                                            String atlasDirName,
                                            String atlasPrefix) throws IOException {
        if (entries.isEmpty()) {
            return;
        }

        VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Generating packed atlas with %d sprites", entries.size()));

        List<TintTask> tintTasks = new ArrayList<>();
        Map<Integer, String> pagePathMap = new java.util.HashMap<>();
        Map<String, AtlasRequest> requestByKey = new LinkedHashMap<>();
        Map<Integer, Integer> pageToUdim = new java.util.HashMap<>();

        // Collect and tint
        for (Map.Entry<String, ExportContext.TintAtlas> entry : entries.entrySet()) {
            String spriteKey = entry.getKey();
            ExportContext.TintAtlas atlas = entry.getValue();
            atlas.placements.clear();

            if (atlas.tintToIndex.isEmpty()) {
                registerTint(ctx, spriteKey, DEFAULT_TINT);
            }

            int tintSlots = Math.max(1, Math.min(MAX_TINT_SLOTS, atlas.nextIndex.get()));
            int[] tintBySlot = resolveTintSlots(atlas, tintSlots);

            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen] Processing sprite: %s (tintSlots=%d)", spriteKey, tintSlots));
            }

            for (int i = 0; i < tintSlots; i++) {
                tintTasks.add(new TintTask(spriteKey, i, tintBySlot[i]));
            }
        }

        Set<String> uniqueSprites = tintTasks.stream()
            .map(TintTask::spriteKey)
            .collect(Collectors.toSet());

        Map<String, BufferedImage> preloaded = uniqueSprites.parallelStream()
            .collect(Collectors.toConcurrentMap(
                key -> key,
                key -> {
                    BufferedImage base = loadTextureForAtlas(ctx, key);
                    if (base == null) {
                        VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][WARN] sprite=%s missing texture, using placeholder", key));
                        base = createMissingTexture();
                    }
                    return base;
                },
                (a, b) -> a
            ));

        // Parallel tinting to utilize multiple cores
        long tTint = VoxelBridgeLogger.now();
        List<AtlasRequest> requests = tintTasks.parallelStream()
            .map(task -> {
                BufferedImage base = preloaded.get(task.spriteKey());
                if (base == null) {
                    base = createMissingTexture();
                }
                return new AtlasRequest(task.spriteKey(), task.tintIndex(), tintTile(base, task.tint()));
            })
            .collect(Collectors.toList());
        // Ensure deterministic order before packing
        requests.sort(Comparator
            .comparing((AtlasRequest r) -> r.spriteKey)
            .thenComparingInt(r -> r.tintIndex));
        VoxelBridgeLogger.duration("atlas_tinting", VoxelBridgeLogger.elapsedSince(tTint));

        // Pack using MaxRects (no rotation)
        long tPack = VoxelBridgeLogger.now();
        int atlasSize = ExportRuntimeConfig.getAtlasSize().getSize();
        TextureAtlasPacker packer = new TextureAtlasPacker(atlasSize, false);
        int counter = 0;
        for (AtlasRequest req : requests) {
            String key = req.spriteKey + "#t" + req.tintIndex + "#" + counter++;
            requestByKey.put(key, req);
            packer.addTexture(key, req.image);
        }

        Path atlasDir = outDir.resolve(atlasDirName);
        Files.createDirectories(atlasDir);

        Map<String, TextureAtlasPacker.Placement> packed = packer.pack(atlasDir, atlasPrefix);
        for (Map.Entry<String, TextureAtlasPacker.Placement> entry : packed.entrySet()) {
            String key = entry.getKey();
            AtlasRequest req = requestByKey.get(key);
            if (req == null) {
                continue;
            }
            TextureAtlasPacker.Placement p = entry.getValue();

            int tileU = p.page() % 10;
            int tileV = p.page() / 10;
            // Double precision here to maximize UV accuracy before storage
            double u0d = tileU + (double) p.x() / atlasSize;
            double v0d = -tileV + (double) p.y() / atlasSize; // Keep UDIM vertical flip consistent
            double u1d = tileU + (double) (p.x() + p.width()) / atlasSize;
            double v1d = -tileV + (double) (p.y() + p.height()) / atlasSize;
            float u0 = (float) u0d;
            float v0 = (float) v0d;
            float u1 = (float) u1d;
            float v1 = (float) v1d;

            ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(req.spriteKey);
            TexturePlacement placement = new TexturePlacement(
                    p.page(), tileU, tileV, p.x(), p.y(), p.width(), p.height(),
                    u0, v0, u1, v1, atlasDirName + "/" + atlasPrefix + p.udim() + ".png");
            atlas.placements.put(req.tintIndex, placement);
            atlas.usesAtlas = true;
            atlas.texW = atlasSize;
            atlas.texH = atlasSize;
            atlas.cols = 1;
            pagePathMap.putIfAbsent(p.page(), atlasDirName + "/" + atlasPrefix + p.udim() + ".png");
            pageToUdim.putIfAbsent(p.page(), p.udim());

            // Mirror placement to block entity atlas map for unified atlas usage
            if (isBlockEntitySprite(req.spriteKey)) {
                int udim = p.udim();
                BlockEntityAtlasPlacement bePlacement = new BlockEntityAtlasPlacement(
                    p.page(), udim, p.x(), p.y(), p.width(), p.height(), atlasSize
                );
                ctx.getBlockEntityAtlasPlacements().put(req.spriteKey, bePlacement);
            }
        }

        // Record material paths per sprite (use first placement page)
        for (Map.Entry<String, ExportContext.TintAtlas> entry : entries.entrySet()) {
            ExportContext.TintAtlas atlas = entry.getValue();
            TexturePlacement placement = atlas.placements.getOrDefault(0, atlas.placements.values().stream().findFirst().orElse(null));
            if (placement != null) {
                String rel = placement.path() != null ? placement.path() : pagePathMap.getOrDefault(placement.page(), atlasDirName + "/" + atlasPrefix + "1001.png");
                ctx.getMaterialPaths().put(entry.getKey(), rel);
            }
        }
        VoxelBridgeLogger.duration("atlas_packing_and_write", VoxelBridgeLogger.elapsedSince(tPack));

        // Generate PBR atlases aligned to the same layout
        long tPbr = VoxelBridgeLogger.now();
        generatePbrAtlases(ctx, outDir, entries, new HashSet<>(pageToUdim.keySet()), atlasSize, atlasDirName, atlasPrefix, pageToUdim);
        VoxelBridgeLogger.duration("pbr_atlas_generation", VoxelBridgeLogger.elapsedSince(tPbr));
    }

    public static float[] remapUV(ExportContext ctx, String spriteKey, int tint, float u, float v) {
        boolean animated = ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(spriteKey);
        if (ExportRuntimeConfig.getAtlasMode() != AtlasMode.ATLAS || animated) {
            return new float[]{u, v};
        }

        int normalizedTint = sanitizeTintValue(tint);
        int tintIndex = getTintIndex(ctx, spriteKey, normalizedTint);
        ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
        if (atlas == null) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][WARN] No atlas found for %s", spriteKey));
            return new float[]{u, v};
        }
        if (atlas.placements.isEmpty()) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][WARN] Atlas for %s has no placements", spriteKey));
            return new float[]{u, v};
        }

        TexturePlacement placement = atlas.placements.getOrDefault(tintIndex, atlas.placements.get(0));
        if (placement == null) {
            placement = atlas.placements.values().stream().findFirst().orElse(null);
        }
        if (placement == null) {
            VoxelBridgeLogger.error(LogModule.TEXTURE_ATLAS, String.format("[RemapUV][ERROR] No placement for %s tintIndex=%d", spriteKey, tintIndex));
            return new float[]{u, v};
        }

        double uu = placement.u0() + (double) u * (placement.u1() - placement.u0());
        double vv = placement.v0() + (double) v * (placement.v1() - placement.v0());
        return new float[]{(float) uu, (float) vv};
    }

    private static int sanitizeTintValue(int tint) {
        if (tint == -1) {
            return DEFAULT_TINT;
        }
        return tint & 0xFFFFFF;
    }

    private static int reserveTintSlot(ExportContext.TintAtlas atlas, int tint) {
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

    private static int[] resolveTintSlots(ExportContext.TintAtlas atlas, int tintSlots) {
        int[] result = new int[tintSlots];
        boolean[] filled = new boolean[tintSlots];
        atlas.indexToTint.forEach((index, tint) -> {
            if (index >= 0 && index < tintSlots && !filled[index]) {
                result[index] = tint;
                filled[index] = true;
            }
        });
        if (tintSlots > 0 && !filled[0]) {
            result[0] = DEFAULT_TINT;
            filled[0] = true;
        }
        int last = tintSlots > 0 ? result[0] : DEFAULT_TINT;
        for (int i = 1; i < tintSlots; i++) {
            if (!filled[i]) {
                result[i] = last;
            } else {
                last = result[i];
            }
        }
        return result;
    }

    private static BufferedImage tintTile(BufferedImage tile, int tint) {
        int w = tile.getWidth();
        int h = tile.getHeight();

        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int[] srcData;
        int[] dstData;
        int srcStride = w;
        int srcOffset = 0;
        var raster = tile.getRaster();
        if (raster.getDataBuffer() instanceof DataBufferInt db && raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm) {
            srcData = db.getData();
            srcStride = sm.getScanlineStride();
            srcOffset = db.getOffset();
        } else {
            srcData = tile.getRGB(0, 0, w, h, null, 0, w);
        }
        dstData = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();

        float rMul = ((tint >> 16) & 0xFF) / 255f;
        float gMul = ((tint >> 8) & 0xFF) / 255f;
        float bMul = (tint & 0xFF) / 255f;

        int dstIdx = 0;
        int srcRowStart = srcOffset;
        for (int y = 0; y < h; y++) {
            int srcIdx = srcRowStart;
            for (int x = 0; x < w; x++, srcIdx++, dstIdx++) {
                int argb = srcData[srcIdx];
                if ((argb & 0xFF000000) == 0) {
                    continue;
                }
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int rr = (int) (r * rMul);
                int gg = (int) (g * gMul);
                int bb = (int) (b * bMul);
                dstData[dstIdx] = (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
            srcRowStart += srcStride;
        }

        return dst;
    }

    private static BufferedImage createMissingTexture() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean pink = ((x / 4) + (y / 4)) % 2 == 0;
                img.setRGB(x, y, pink ? 0xFFFF00FF : 0xFF000000);
            }
        }
        return img;
    }

    private static boolean isPbrSprite(String key) {
        return key != null && (key.endsWith("_n") || key.endsWith("_s"));
    }


    /**
     * Generate PBR atlases (normal/specular) using the same layout as the albedo atlas.
     * Missing channels are filled with default tiles.
     *
     * Uses PbrAtlasWriter to generate PBR channel atlases aligned to the base texture placements.
     * Each tint variant placement gets its own PBR texture lookup.
     */
    private static void generatePbrAtlases(ExportContext ctx,
                                           Path outDir,
                                           Map<String, ExportContext.TintAtlas> entries,
                                           Set<Integer> usedPages,
                                           int atlasSize,
                                           String atlasDirName,
                                           String basePrefix,
                                           Map<Integer, Integer> pageToUdim) throws IOException {
        if (entries.isEmpty() || usedPages.isEmpty()) {
            return;
        }

        Path atlasDir = outDir.resolve(atlasDirName);

        // Build a flat map of all placements (spriteKey#tintIndex -> placement)
        // This allows PbrAtlasWriter to handle each tint variant independently
        Map<String, PbrAtlasWriter.Placement> flatPlacements = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ExportContext.TintAtlas> entry : entries.entrySet()) {
            String spriteKey = entry.getKey();
            ExportContext.TintAtlas atlas = entry.getValue();
            for (Map.Entry<Integer, ExportContext.TexturePlacement> placementEntry : atlas.placements.entrySet()) {
                int tintIndex = placementEntry.getKey();
                ExportContext.TexturePlacement placement = placementEntry.getValue();
                String key = tintIndex == 0 ? spriteKey : spriteKey + "#t" + tintIndex;
                flatPlacements.put(key, new PbrAtlasWriter.TexturePlacementAdapter(placement));
            }
        }

        // OPTIMIZATION: Use configured thread count instead of hardcoded 2
        // Respects user's ExportRuntimeConfig.exportThreadCount setting
        // Default: Runtime.getRuntime().availableProcessors()
        int threadCount = ExportRuntimeConfig.getExportThreadCount();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, threadCount));
        try {
            Future<?> futureNormal = executor.submit(() -> {
                try {
                    PbrAtlasWriter.PbrAtlasConfig normalConfig = new PbrAtlasWriter.PbrAtlasConfig(
                        atlasDir, atlasSize, basePrefix + "n_",
                        PbrTextureHelper.DEFAULT_NORMAL_COLOR, usedPages, pageToUdim
                    );
                    PbrAtlasWriter.generatePbrAtlas(normalConfig, flatPlacements, key -> {
                        String spriteKey = key.contains("#") ? key.substring(0, key.indexOf("#")) : key;
                        BufferedImage normalImage = ctx.getCachedSpriteImage(spriteKey + "_n");
                        if (normalImage != null && !key.contains("#")) {
                            PbrAtlasWriter.Placement p = flatPlacements.get(key);
                            int udim = pageToUdim.getOrDefault(p.page(), p.page() + 1001);
                            String normalPath = atlasDirName + "/" + basePrefix + "n_" + udim + ".png";
                            ctx.getMaterialPaths().put(spriteKey + "_n", normalPath);
                        }
                        return normalImage;
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> futureSpec = executor.submit(() -> {
                try {
                    PbrAtlasWriter.PbrAtlasConfig specConfig = new PbrAtlasWriter.PbrAtlasConfig(
                        atlasDir, atlasSize, basePrefix + "s_",
                        PbrTextureHelper.DEFAULT_SPECULAR_COLOR, usedPages, pageToUdim
                    );
                    PbrAtlasWriter.generatePbrAtlas(specConfig, flatPlacements, key -> {
                        String spriteKey = key.contains("#") ? key.substring(0, key.indexOf("#")) : key;
                        BufferedImage specImage = ctx.getCachedSpriteImage(spriteKey + "_s");
                        if (specImage != null && !key.contains("#")) {
                            PbrAtlasWriter.Placement p = flatPlacements.get(key);
                            int udim = pageToUdim.getOrDefault(p.page(), p.page() + 1001);
                            String specPath = atlasDirName + "/" + basePrefix + "s_" + udim + ".png";
                            ctx.getMaterialPaths().put(spriteKey + "_s", specPath);
                        }
                        return specImage;
                    });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            futureNormal.get();
            futureSpec.get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("PBR atlas generation failed", cause);
        } finally {
            executor.shutdown();
        }
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private static String safe(String spriteKey) {
        return spriteKey.replace(':', '_').replace('/', '_');
    }

    private static boolean isBlockEntitySprite(String spriteKey) {
        return spriteKey != null && (spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:"));
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
        Path animDir = outDir.resolve("textures").resolve("animated");
        Files.createDirectories(animDir);

        VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Starting animation export...");
        VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Total animations in repository: " + repo.getAnimatedCache().size());

        if (whitelist != null && !whitelist.isEmpty()) {
            int whitelistedCount = 0;
            for (String key : repo.getAnimatedCache().keySet()) {
                if (whitelist.contains(key)) {
                    whitelistedCount++;
                }
            }
            VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] Whitelisted animations: " + whitelistedCount);
        }

        int exportCount = 0;
        for (String spriteKey : repo.getAnimatedCache().keySet()) {
            // Skip exporting standalone PBR variants; they will be embedded with the base sprite
            if (spriteKey.endsWith("_n") || spriteKey.endsWith("_s")) {
                continue;
            }
            if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(spriteKey)) {
                continue; // Skip animations outside the export set
            }
            AnimatedFrameSet frames = repo.getAnimation(spriteKey);
            if (frames == null || frames.isEmpty()) {
                continue; // Skip empty markers
            }

            // Export animation frames
            String baseName = primitiveBaseName(ctx, spriteKey);
            Path spriteDir = animDir.resolve(baseName);
            Files.createDirectories(spriteDir);

            int frameCount = frames.frames().size();
            for (int i = 0; i < frameCount; i++) {
                String idx = String.format("%03d", i);
                Path framePath = spriteDir.resolve(baseName + "_" + idx + ".png");
                try {
                    javax.imageio.ImageIO.write(frames.frames().get(i), "PNG", framePath.toFile());
                } catch (IOException e) {
                    VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] Failed to write frame " + framePath + ": " + e.getMessage());
                }
            }

            // Export PBR frames if available
            AnimatedFrameSet normalFrames = repo.getAnimation(spriteKey + "_n");
            if (normalFrames != null && !normalFrames.isEmpty()) {
                for (int i = 0; i < frameCount; i++) {
                    String idx = String.format("%03d", i);
                    int normalIdx = i % normalFrames.frames().size();
                    Path framePath = spriteDir.resolve(baseName + "_" + idx + "_n.png");
                    try {
                        javax.imageio.ImageIO.write(normalFrames.frames().get(normalIdx), "PNG", framePath.toFile());
                    } catch (IOException e) {
                        VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] Failed to write normal frame " + framePath);
                    }
                }
            }

            AnimatedFrameSet specFrames = repo.getAnimation(spriteKey + "_s");
            if (specFrames != null && !specFrames.isEmpty()) {
                for (int i = 0; i < frameCount; i++) {
                    String idx = String.format("%03d", i);
                    int specIdx = i % specFrames.frames().size();
                    Path framePath = spriteDir.resolve(baseName + "_" + idx + "_s.png");
                    try {
                        javax.imageio.ImageIO.write(specFrames.frames().get(specIdx), "PNG", framePath.toFile());
                    } catch (IOException e) {
                        VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] Failed to write spec frame " + framePath);
                    }
                }
            }

            // Export original .mcmeta if available (expected for all animated textures)
            copyOriginalMcmeta(ctx, spriteKey, spriteDir, baseName);

            exportCount++;
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                "[Animation] Exported %s: %d frames to %s",
                spriteKey, frameCount, spriteDir.getFileName()
            ));
        }

        VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Export completed: %d animations", exportCount));

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
            "[Animation] ===== EXPORT COMPLETE: %d animations exported =====", exportCount
        ));
    }

    /**
     * Ensures we flag animations by scanning the full texture strip (not just first frame).
     */
    private static void ensureAnimationDetection(ExportContext ctx, String spriteKey) {
        if (ctx.getTextureRepository().hasAnimation(spriteKey)) {
            return;
        }
        // Try atlas sprite first to leverage SpriteContents metadata
        try {
            TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            ResourceLocation spriteLoc = com.voxelbridge.util.ResourceLocationUtil.sanitize(spriteKey);
            TextureAtlasSprite sprite = atlas.getSprite(spriteLoc);
            if (sprite != null) {
                AnimatedTextureHelper.extractFromSprite(spriteKey, sprite, ctx.getTextureRepository());
                if (ctx.getTextureRepository().hasAnimation(spriteKey)) {
                    return;
                }
            }
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, "[Animation][WARN] Atlas probe failed for " + spriteKey + ": " + e.getMessage());
        }
        ResourceLocation textureLocation = TextureLoader.spriteKeyToTexturePNG(spriteKey);
        // Prefer metadata-driven detection to catch animations even when frame strips are square
        AnimatedTextureHelper.detectFromMetadata(spriteKey, textureLocation, ctx.getTextureRepository());
    }

    /**
     * Loads a texture for atlas generation (block + block entity).
     */
    private static BufferedImage loadTextureForAtlas(ExportContext ctx, String spriteKey) {
        ResourceLocation textureLocation = TextureLoader.spriteKeyToTexturePNG(spriteKey);

        BufferedImage cached = ctx.getTextureRepository().get(textureLocation);
        if (cached == null) {
            cached = ctx.getCachedSpriteImage(spriteKey);
        }
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
        BufferedImage diskImage = TextureLoader.readTexture(textureLocation, ExportRuntimeConfig.isAnimationEnabled());
        if (diskImage != null) {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][DISK HIT] Loaded %s from disk (%dx%d)",
                    spriteKey, diskImage.getWidth(), diskImage.getHeight()));
            }
            ctx.getTextureRepository().put(textureLocation, spriteKey, diskImage);
        } else {
            if (VoxelBridgeLogger.isDebugEnabled(LogModule.TEXTURE_ATLAS)) {
                VoxelBridgeLogger.info(LogModule.TEXTURE_ATLAS, String.format("[AtlasGen][DISK MISS] Failed to load %s from disk", spriteKey));
            }
        }
        return diskImage;
    }

    private static String primitiveBaseName(ExportContext ctx, String spriteKey) {
        if (spriteKey == null) {
            return "unknown";
        }
        // Align animation export folder/file naming with glTF animated primitive key
        String base = safe(spriteKey);

        // Overlay suffix if sprite itself is an overlay variant
        boolean overlay = spriteKey.endsWith("_overlay") || spriteKey.contains("/overlay") || spriteKey.contains(":overlay");
        if (overlay && !base.endsWith("_overlay")) {
            base = base + "_overlay";
        }
        if (!base.endsWith("_animated")) {
            base = base + "_animated";
        }
        return base;
    }

    private static void copyOriginalMcmeta(ExportContext ctx, String spriteKey, Path spriteDir, String baseName) {
        try {
            ResourceLocation pngLoc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            if (pngLoc == null) {
                return;
            }
            ResourceLocation mcmetaLoc = ResourceLocation.fromNamespaceAndPath(
                pngLoc.getNamespace(),
                pngLoc.getPath() + ".mcmeta"
            );
            var resOpt = ctx.getMc().getResourceManager().getResource(mcmetaLoc);
            if (resOpt.isEmpty()) {
                return;
            }
            var res = resOpt.get();
            Path target = spriteDir.resolve(baseName + ".mcmeta");
            try (var in = res.open()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Copied original mcmeta for %s -> %s", spriteKey, target.getFileName()));
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, String.format("[Animation][WARN] Failed to copy mcmeta for %s: %s", spriteKey, e.getMessage()));
        }
    }
}





