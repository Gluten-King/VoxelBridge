package com.voxelbridge.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared export context used by every exporter (thread-safe).
 */
@OnlyIn(Dist.CLIENT)
public final class ExportContext {

    private final Minecraft mc;
    private final BlockColors blockColors;

    // Thread-safe containers shared by multiple worker threads.
    private final Map<String, TintAtlas> atlasBook = new ConcurrentHashMap<>();
    private final Map<String, String> materialNames = new ConcurrentHashMap<>();
    private final Map<String, String> materialPaths = new ConcurrentHashMap<>();
    private final Map<Integer, TexturePlacement> colorMap = new ConcurrentHashMap<>();
    private final AtomicInteger nextColorSlot = new AtomicInteger(1); // 0 reserved for white
    private final Set<Long> consumedBlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, EntityTexture> entityTextures = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> generatedEntityTextures = new ConcurrentHashMap<>();
    private final Map<String, BlockEntityAtlasPlacement> blockEntityAtlasPlacements = new ConcurrentHashMap<>();
    private final Map<String, String> overlayMappings = new ConcurrentHashMap<>();  // base spriteKey -> overlay spriteKey
    private boolean blockEntityExportEnabled = true;
    private CoordinateMode coordinateMode = CoordinateMode.CENTERED;

    public ExportContext(Minecraft mc) {
        this.mc = mc;
        this.blockColors = mc.getBlockColors();
    }

    public Minecraft getMc() {
        return mc;
    }

    public BlockColors getBlockColors() {
        return blockColors;
    }

    public Map<String, TintAtlas> getAtlasBook() {
        return atlasBook;
    }

    public Map<String, String> getMaterialNames() {
        return materialNames;
    }

    public Map<String, String> getMaterialPaths() {
        return materialPaths;
    }

    public Map<Integer, TexturePlacement> getColorMap() {
        return colorMap;
    }

    public AtomicInteger getNextColorSlot() {
        return nextColorSlot;
    }

    /**
     * Gets or creates the tint atlas for a sprite.
     */
    public TintAtlas getOrCreateTintAtlas(String spriteKey) {
        return atlasBook.computeIfAbsent(spriteKey, k -> new TintAtlas());
    }

    /**
     * Gets or creates a safe material name (thread-safe).
     */
    public String getMaterialNameForSprite(String spriteKey) {
        return materialNames.computeIfAbsent(spriteKey, k -> "mat_" + safe(k));
    }

    public boolean isBlockConsumed(BlockPos pos) {
        return consumedBlocks.contains(pos.asLong());
    }

    public void markBlockConsumed(BlockPos pos) {
        consumedBlocks.add(pos.asLong());
    }

    public void resetConsumedBlocks() {
        consumedBlocks.clear();
    }

    public Map<String, EntityTexture> getEntityTextures() {
        return entityTextures;
    }

    public Map<String, BlockEntityAtlasPlacement> getBlockEntityAtlasPlacements() {
        return blockEntityAtlasPlacements;
    }

    public Map<String, String> getOverlayMappings() {
        return overlayMappings;
    }

    public void clearEntityTextures() {
        entityTextures.clear();
        generatedEntityTextures.clear();
    }

    public Map<String, BufferedImage> getGeneratedEntityTextures() {
        return generatedEntityTextures;
    }

    public void registerGeneratedEntityTexture(String key, BufferedImage image) {
        generatedEntityTextures.put(key, image);
    }

    public boolean isBlockEntityExportEnabled() {
        return blockEntityExportEnabled;
    }

    public void setBlockEntityExportEnabled(boolean enabled) {
        this.blockEntityExportEnabled = enabled;
    }

    public CoordinateMode getCoordinateMode() {
        return coordinateMode;
    }

    public void setCoordinateMode(CoordinateMode mode) {
        this.coordinateMode = mode;
    }

    private static String safe(String s) {
        return s.replace(':', '_').replace('/', '_').replace(' ', '_');
    }

    /**
     * Tracks tint variants gathered for a sprite and the atlas placement.
     */
    public static final class TintAtlas {
        public final Map<Integer, Integer> tintToIndex = new ConcurrentHashMap<>();
        public final Map<Integer, Integer> indexToTint = new ConcurrentHashMap<>();
        public final Map<Integer, TexturePlacement> placements = new ConcurrentHashMap<>();
        public final AtomicInteger nextIndex = new AtomicInteger();
        public volatile int cols = 0;
        public volatile Path atlasFile;
        public volatile int texW = 0, texH = 0;
        public volatile boolean usesAtlas = true;
    }

    public record TexturePlacement(int page, int tileU, int tileV, int x, int y, int w, int h,
                                   float u0, float v0, float u1, float v1, String path) {}

    public record EntityTexture(ResourceLocation location, int width, int height) {}

    /**
     * Stores atlas placement information for block entity textures.
     */
    public static record BlockEntityAtlasPlacement(int page, int udim, int x, int y, int width, int height, int atlasSize) {
        public float u0() {
            int tileU = page % 10;
            return tileU + (float) x / atlasSize;
        }
        public float v0() {
            int tileV = page / 10;
            // Fix UDIM V coordinate: negate tileV to compensate for coordinate flip elsewhere
            return -tileV + (float) y / atlasSize;
        }
        public float u1() {
            int tileU = page % 10;
            return tileU + (float) (x + width) / atlasSize;
        }
        public float v1() {
            int tileV = page / 10;
            // Fix UDIM V coordinate: negate tileV to compensate for coordinate flip elsewhere
            return -tileV + (float) (y + height) / atlasSize;
        }
    }

}
