package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.config.ExportRuntimeConfig.AtlasMode;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportContext.TexturePlacement;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.export.scene.gltf.BlockEntityAtlasPacker;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

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
        atlas.tintToIndex.put(0xFFFFFF, 0);  // tint 0xFFFFFF → slot 0
        atlas.indexToTint.put(0, 0xFFFFFF);
        atlas.nextIndex.set(1);  // Next available slot starts from 1

        ExportLogger.log("[TextureAtlas] Reserved slot 0 for transparent texture (16x16)");
    }

    public static void registerTint(ExportContext ctx, String spriteKey, int tint) {
        ExportContext.TintAtlas atlas = ctx.getOrCreateTintAtlas(spriteKey);
        int normalized = sanitizeTintValue(tint);
        atlas.tintToIndex.computeIfAbsent(normalized, key -> {
            int slot = reserveTintSlot(atlas, key);
            int totalSlots = Math.max(1, Math.min(MAX_TINT_SLOTS, atlas.nextIndex.get()));
            ExportLogger.log(String.format("[Tint] sprite=%s tint=%06X slot=%d totalSlots=%d",
                    spriteKey, normalized, slot, totalSlots));
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
        System.out.println("[TextureAtlasManager] Generating atlases (mode=" + atlasMode + ")...");
        ExportLogger.log("[AtlasGen] ===== ATLAS GENERATION START =====");
        ExportLogger.log(String.format("[AtlasGen] Atlas mode: %s", atlasMode));
        ExportLogger.log(String.format("[AtlasGen] Total sprites in atlasBook: %d", ctx.getAtlasBook().size()));

        Map<String, ExportContext.TintAtlas> blockEntries = new java.util.LinkedHashMap<>();
        ctx.getAtlasBook().forEach((key, atlas) -> {
            boolean isInCache = ctx.getCachedSpriteImage(key) != null;
            ExportLogger.log(String.format("[AtlasGen] Sprite registered: %s (inCache=%s, tints=%d)",
                key, isInCache, atlas.nextIndex.get()));
            if (!isBlockEntitySprite(key)) {
                blockEntries.put(key, atlas);
            }
        });

        ExportLogger.log(String.format("[AtlasGen] Block sprites to process: %d", blockEntries.size()));

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
                ExportLogger.log(String.format("[AtlasGen][WARN] sprite=%s missing texture, using placeholder", spriteKey));
                base = createMissingTexture();
            }

            BufferedImage outputImage = tintTile(base, tintBySlot[0]);
            String relativePath = subDir + safe(spriteKey) + ".png";
            Path target = outDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            ImageIO.write(outputImage, "png", target.toFile());
            atlas.atlasFile = target;
            atlas.texW = outputImage.getWidth();
            atlas.texH = outputImage.getHeight();
            atlas.usesAtlas = false;
            atlas.cols = 1;
            atlas.placements.clear();
            ctx.getMaterialPaths().put(spriteKey, relativePath);

            ExportLogger.log(String.format("[AtlasGen] sprite=%s mode=individual path=%s", spriteKey, relativePath));
        }
    }

    private record AtlasRequest(String spriteKey, int tintIndex, BufferedImage image) {}

    private static void generatePackedAtlas(ExportContext ctx,
                                            Path outDir,
                                            Map<String, ExportContext.TintAtlas> entries,
                                            String atlasDirName,
                                            String atlasPrefix) throws IOException {
        if (entries.isEmpty()) {
            return;
        }

        ExportLogger.log(String.format("[AtlasGen] Generating packed atlas with %d sprites", entries.size()));

        List<AtlasRequest> requests = new ArrayList<>();
        Map<Integer, String> pagePathMap = new java.util.HashMap<>();
        Map<String, AtlasRequest> requestByKey = new LinkedHashMap<>();

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

            ExportLogger.log(String.format("[AtlasGen] Processing sprite: %s (tintSlots=%d)", spriteKey, tintSlots));

            // [FIX] Use updated loadTextureForAtlas
            BufferedImage base = loadTextureForAtlas(ctx, spriteKey);

            if (base == null) {
                ExportLogger.log(String.format("[AtlasGen][WARN] sprite=%s missing texture, using placeholder", spriteKey));
                base = createMissingTexture();
            }

            for (int i = 0; i < tintSlots; i++) {
                BufferedImage tinted = tintTile(base, tintBySlot[i]);
                requests.add(new AtlasRequest(spriteKey, i, tinted));
            }
        }

        // Pack using MaxRects (no rotation)
        int atlasSize = ExportRuntimeConfig.getAtlasSize().getSize();
        BlockEntityAtlasPacker packer = new BlockEntityAtlasPacker(atlasSize, false);
        int counter = 0;
        for (AtlasRequest req : requests) {
            String key = req.spriteKey + "#t" + req.tintIndex + "#" + counter++;
            requestByKey.put(key, req);
            packer.addTexture(key, req.image);
        }

        Path atlasDir = outDir.resolve(atlasDirName);
        Files.createDirectories(atlasDir);

        Map<String, BlockEntityAtlasPacker.Placement> packed = packer.pack(atlasDir, atlasPrefix);
        for (Map.Entry<String, BlockEntityAtlasPacker.Placement> entry : packed.entrySet()) {
            String key = entry.getKey();
            AtlasRequest req = requestByKey.get(key);
            if (req == null) {
                continue;
            }
            BlockEntityAtlasPacker.Placement p = entry.getValue();

            int tileU = p.page() % 10;
            int tileV = p.page() / 10;
            float u0 = tileU + (float) p.x() / atlasSize;
            // Keep UDIM vertical flip consistent with previous behavior
            float v0 = -tileV + (float) p.y() / atlasSize;
            float u1 = tileU + (float) (p.x() + p.width()) / atlasSize;
            float v1 = -tileV + (float) (p.y() + p.height()) / atlasSize;

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
    }

    public static float[] remapUV(ExportContext ctx, String spriteKey, int tint, float u, float v) {
        if (ExportRuntimeConfig.getAtlasMode() != AtlasMode.ATLAS) {
            return new float[]{u, v};
        }

        if (isBlockEntitySprite(spriteKey)) {
            return new float[]{u, v};
        }

        int normalizedTint = sanitizeTintValue(tint);
        int tintIndex = getTintIndex(ctx, spriteKey, normalizedTint);
        ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
        if (atlas == null) {
            ExportLogger.log(String.format("[RemapUV][WARN] No atlas found for %s", spriteKey));
            return new float[]{u, v};
        }
        if (atlas.placements.isEmpty()) {
            ExportLogger.log(String.format("[RemapUV][WARN] Atlas for %s has no placements", spriteKey));
            return new float[]{u, v};
        }

        TexturePlacement placement = atlas.placements.getOrDefault(tintIndex, atlas.placements.get(0));
        if (placement == null) {
            placement = atlas.placements.values().stream().findFirst().orElse(null);
        }
        if (placement == null) {
            ExportLogger.log(String.format("[RemapUV][ERROR] No placement for %s tintIndex=%d", spriteKey, tintIndex));
            return new float[]{u, v};
        }

        float uu = placement.u0() + u * (placement.u1() - placement.u0());
        float vv = placement.v0() + v * (placement.v1() - placement.v0());
        return new float[]{uu, vv};
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
        float[] mul = TextureLoader.rgbMul(tint);
        BufferedImage dst = new BufferedImage(tile.getWidth(), tile.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < tile.getHeight(); y++) {
            for (int x = 0; x < tile.getWidth(); x++) {
                int argb = tile.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int rr = Math.min(255, (int) (r * mul[0]));
                int gg = Math.min(255, (int) (g * mul[1]));
                int bb = Math.min(255, (int) (b * mul[2]));
                dst.setRGB(x, y, (a << 24) | (rr << 16) | (gg << 8) | bb);
            }
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

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private static String safe(String spriteKey) {
        return spriteKey.replace(':', '_').replace('/', '_');
    }

    private static boolean isBlockEntitySprite(String spriteKey) {
        return spriteKey != null && (spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:"));
    }

    /**
     * Loads a texture for atlas generation.
     * Only handles normal block textures - entity textures should not be in the atlas.
     */
    private static BufferedImage loadTextureForAtlas(ExportContext ctx, String spriteKey) {
        // [FIX] 优先检查内存缓存（由 BlockExporter 填充的 CTM 纹理）
        BufferedImage cached = ctx.getCachedSpriteImage(spriteKey);
        if (cached != null) {
            ExportLogger.log(String.format("[AtlasGen][CACHE HIT] Loaded %s from sprite cache (%dx%d)",
                spriteKey, cached.getWidth(), cached.getHeight()));
            return cached;
        }

        // 2. 回退到从磁盘读取（原版纹理）
        ExportLogger.log(String.format("[AtlasGen][CACHE MISS] %s not in cache, trying disk", spriteKey));
        ResourceLocation textureLocation = TextureLoader.spriteKeyToTexturePNG(spriteKey);
        BufferedImage diskImage = TextureLoader.readTexture(textureLocation);
        if (diskImage != null) {
            ExportLogger.log(String.format("[AtlasGen][DISK HIT] Loaded %s from disk (%dx%d)",
                spriteKey, diskImage.getWidth(), diskImage.getHeight()));
        } else {
            ExportLogger.log(String.format("[AtlasGen][DISK MISS] Failed to load %s from disk", spriteKey));
        }
        return diskImage;
    }
}
