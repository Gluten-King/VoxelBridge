package com.voxelbridge.export;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.texture.TextureAccess;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.core.util.color.ColorMapAccess;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.texture.ExportColorMapAccess;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.texture.TexturePathResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared export context used by every exporter (thread-safe).
 * Wraps Minecraft runtime services plus MC-agnostic export state.
 */
public final class ExportContext {

    private final SamplerContext sampler;
    private final ExportState state;
    private final TextureAccess<TextureAtlasSprite> textureAccess;
    private final ColorMapAccess colorMapAccess;

    public ExportContext(Minecraft mc, TextureAccess<TextureAtlasSprite> textureAccess) {
        this.sampler = new SamplerContext(mc);
        this.state = new ExportState();
        this.textureAccess = textureAccess;
        this.colorMapAccess = new ExportColorMapAccess(this);
    }

    public SamplerContext sampler() {
        return sampler;
    }

    public ExportState state() {
        return state;
    }

    public Minecraft getMc() {
        return sampler.getMc();
    }

    public void runOnMainThread(Runnable task) {
        sampler.getMc().executeBlocking(task);
    }

    public TextureAccess<TextureAtlasSprite> getTextureAccess() {
        return textureAccess;
    }

    public ColorMapAccess getColorMapAccess() {
        return colorMapAccess;
    }

    public ColorMode getColorMode() {
        return ExportRuntimeConfig.getColorMode();
    }

    public BlockColors getBlockColors() {
        return sampler.getBlockColors();
    }

    public Map<String, ExportState.TintAtlas> getAtlasBook() {
        return state.getAtlasBook();
    }

    public Map<String, String> getMaterialNames() {
        return state.getMaterialNames();
    }

    public Map<String, String> getMaterialPaths() {
        return state.getMaterialPaths();
    }

    public Map<String, String> getSpriteToMaterial() {
        return state.getSpriteToMaterial();
    }

    public void registerSpriteMaterial(String spriteKey, String materialKey) {
        state.registerSpriteMaterial(spriteKey, materialKey);
    }

    public String resolveMaterialKey(String spriteKey, String fallbackMaterialKey) {
        if (spriteKey != null && ExportRuntimeConfig.isAnimationEnabled()) {
            TextureRepository repo = state.getTextureRepository();
            if (!repo.hasAnimation(spriteKey)) {
                String resourceKey = textureAccess.spriteKeyToResourceKey(spriteKey);
                AnimatedTextureHelper.detectFromMetadata(this, spriteKey, resourceKey, repo);
            }
            if (repo.hasAnimation(spriteKey)) {
                String base = TexturePathResolver.animationBaseName(spriteKey);
                if (fallbackMaterialKey != null && fallbackMaterialKey.endsWith("_emissive")) {
                    String animSuffix = "_animated";
                    if (base.endsWith(animSuffix)) {
                        String prefix = base.substring(0, base.length() - animSuffix.length());
                        if (!prefix.endsWith("_emissive")) {
                            prefix = prefix + "_emissive";
                        }
                        base = prefix + animSuffix;
                    } else if (!base.endsWith("_emissive")) {
                        base = base + "_emissive";
                    }
                }
                return base;
            }
        }
        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.INDIVIDUAL && spriteKey != null) {
            if (fallbackMaterialKey == null || fallbackMaterialKey.isEmpty()) {
                return spriteKey;
            }
            String merged = spriteKey;
            String base = fallbackMaterialKey;
            boolean stripped;
            do {
                stripped = false;
                if (base.endsWith("_overlay")) {
                    merged = merged + "_overlay";
                    base = base.substring(0, base.length() - "_overlay".length());
                    stripped = true;
                } else if (base.endsWith("_hilight")) {
                    merged = merged + "_hilight";
                    base = base.substring(0, base.length() - "_hilight".length());
                    stripped = true;
                } else if (base.endsWith("_emissive")) {
                    merged = merged + "_emissive";
                    base = base.substring(0, base.length() - "_emissive".length());
                    stripped = true;
                }
            } while (stripped);
            return merged;
        }
        return fallbackMaterialKey;
    }

    public it.unimi.dsi.fastutil.ints.Int2ObjectMap<ExportState.TexturePlacement> getColorMap() {
        return state.getColorMap();
    }

    /**
     * Deduplicates string instances to save memory.
     */
    public String intern(String s) {
        return state.intern(s);
    }

    public AtomicInteger getNextColorSlot() {
        return state.getNextColorSlot();
    }

    /**
     * Gets or creates the tint atlas for a sprite.
     */
    public ExportState.TintAtlas getOrCreateTintAtlas(String spriteKey) {
        return state.getOrCreateTintAtlas(spriteKey);
    }

    /**
     * Gets or creates a safe material name (thread-safe).
     */
    public String getMaterialNameForSprite(String spriteKey) {
        return state.getMaterialNameForSprite(spriteKey);
    }

    public boolean isBlockConsumed(BlockPos pos) {
        return state.isBlockConsumed(pos.asLong());
    }

    public void markBlockConsumed(BlockPos pos) {
        state.markBlockConsumed(pos.asLong());
    }

    public void resetConsumedBlocks() {
        state.resetConsumedBlocks();
    }

    public Map<String, ExportState.EntityTexture> getEntityTextures() {
        return state.getEntityTextures();
    }

    public Map<String, ExportState.BlockEntityAtlasPlacement> getBlockEntityAtlasPlacements() {
        return state.getBlockEntityAtlasPlacements();
    }

    public TextureRepository getTextureRepository() {
        return state.getTextureRepository();
    }

    /**
     * Clears all texture-related state to isolate export sessions.
     */
    public void clearTextureState() {
        state.clearTextureState();
    }

    public void clearEntityTextures() {
        state.clearEntityTextures();
    }

    public Map<String, BufferedImage> getGeneratedEntityTextures() {
        return state.getGeneratedEntityTextures();
    }

    public void registerGeneratedEntityTexture(String key, BufferedImage image) {
        state.registerGeneratedEntityTexture(key, image);
    }

    public void cacheSpriteImage(String spriteKey, BufferedImage image) {
        state.cacheSpriteImage(spriteKey, image);
    }

    public BufferedImage getCachedSpriteImage(String spriteKey) {
        return state.getCachedSpriteImage(spriteKey);
    }

    /**
     * Exposes keys of cached sprite images (including dynamically loaded CTM/PBR companions).
     */
    public Set<String> getCachedSpriteKeys() {
        return state.getCachedSpriteKeys();
    }

    public boolean isBlockEntityExportEnabled() {
        return state.isBlockEntityExportEnabled();
    }

    public void setBlockEntityExportEnabled(boolean enabled) {
        state.setBlockEntityExportEnabled(enabled);
    }

    public CoordinateMode getCoordinateMode() {
        return state.getCoordinateMode();
    }

    public void setCoordinateMode(CoordinateMode mode) {
        state.setCoordinateMode(mode);
    }

    public boolean isVanillaRandomTransformEnabled() {
        return state.isVanillaRandomTransformEnabled();
    }

    public void setVanillaRandomTransformEnabled(boolean enabled) {
        state.setVanillaRandomTransformEnabled(enabled);
    }

    public boolean isDiscoveryMode() {
        return state.isDiscoveryMode();
    }

    public void setDiscoveryMode(boolean discoveryMode) {
        state.setDiscoveryMode(discoveryMode);
    }
}
