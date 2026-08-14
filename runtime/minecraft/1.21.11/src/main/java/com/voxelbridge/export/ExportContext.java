package com.voxelbridge.export;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.core.util.color.ColorMapAccess;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.texture.ExportColorMapAccess;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.texture.MinecraftTextureAccess;
import com.voxelbridge.export.texture.TexturePathResolver;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.contract.RuntimeCapabilities;
import com.voxelbridge.pipeline.contract.RuntimeCapability;
import com.voxelbridge.pipeline.resource.ResourceIds;
import com.voxelbridge.pipeline.resource.TextureResourcePaths;
import com.voxelbridge.pipeline.port.ClientExecutor;
import com.voxelbridge.pipeline.port.RuntimeDiagnostics;
import com.voxelbridge.pipeline.port.SpecialRenderSource;
import com.voxelbridge.pipeline.session.ExportSessionOptions;
import com.voxelbridge.pipeline.session.ExportSession;
import com.voxelbridge.pipeline.session.RuntimeServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Shared export context used by every exporter (thread-safe).
 * Wraps Minecraft runtime services plus MC-agnostic export state.
 */
public final class ExportContext implements AutoCloseable {

    private final SamplerContext sampler;
    private final ExportSession session;
    private final ExportState state;
    private final ExportOptions textureOptions;
    private final MinecraftTextureAccess textureAccess;
    private final ColorMapAccess colorMapAccess;

    public ExportContext(Minecraft mc, Level level, MinecraftTextureAccess textureAccess) {
        this.sampler = new SamplerContext(mc);
        this.textureAccess = textureAccess;
        ExportSessionOptions options = snapshotOptions();
        MinecraftWorldSource12111 worldSource = new MinecraftWorldSource12111(level);
        this.session = new ExportSession(
            new ExportState(),
            new RuntimeServices(
                worldSource,
                new MinecraftBlockGeometrySource12111(
                    level, worldSource, options.vanillaRandomTransform()),
                SpecialRenderSource.EMPTY,
                textureAccess,
                clientExecutor(mc),
                runtimeDiagnostics(),
                RuntimeCapabilities.of(
                    RuntimeCapability.BLOCK_MODEL_QUADS,
                    RuntimeCapability.FACE_OCCLUSION_SHAPE,
                    RuntimeCapability.DYNAMIC_TEXTURE_READBACK,
                    RuntimeCapability.GLYPH_TEXTURE_READBACK)
            ),
            options
        );
        ExportProgressTracker.bind(this.session);
        this.state = session.state();
        this.textureOptions = textureOptions(session.options());
        this.colorMapAccess = new ExportColorMapAccess(this);
    }

    public ExportSession session() {
        return session;
    }

    public ExportOptions textureOptions() {
        return textureOptions;
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

    /** Version-neutral texture port for every resource-key based read. */
    public BufferedImage readTexture(String resourceKey, boolean preserveAnimationStrip) {
        return session.runtime().textures().readTexture(
            ResourceIds.sanitize(resourceKey), preserveAnimationStrip);
    }

    public BufferedImage readTexture(String resourceKey) {
        return readTexture(resourceKey, false);
    }

    /** Reads a runtime sprite using the immutable animation policy captured by this session. */
    public BufferedImage readSprite(TextureAtlasSprite sprite) {
        return textureAccess.readRuntimeSprite(sprite, session.options().animation());
    }

    public String resolveSpriteKey(TextureAtlasSprite sprite) {
        return textureAccess.resolveSpriteKey(sprite);
    }

    public String resourceKeyForSprite(String spriteKey) {
        return spriteKey == null ? null : TextureResourcePaths.fromSpriteKey(spriteKey).toString();
    }

    public boolean hasResource(String resourceKey) {
        return session.runtime().textures().hasResource(ResourceIds.sanitize(resourceKey));
    }

    public byte[] readResource(String resourceKey) {
        return session.runtime().textures().readResource(ResourceIds.sanitize(resourceKey));
    }

    public com.voxelbridge.core.texture.AnimationMetadata readAnimationMetadata(String resourceKey) {
        return session.runtime().textures().readAnimationMetadata(ResourceIds.sanitize(resourceKey));
    }

    public Set<String> listPngResources(String pathPrefix) {
        Set<String> result = new java.util.HashSet<>();
        for (ResourceId resource : session.runtime().textures().listPngResources(pathPrefix)) {
            result.add(resource.toString());
        }
        return Set.copyOf(result);
    }

    public String ensurePngKey(String resourceKey) {
        return TextureResourcePaths.ensurePng(resourceKey).toString();
    }

    public String appendTextureSuffix(String resourceKey, String suffix) {
        return TextureResourcePaths.appendSuffix(resourceKey, suffix).toString();
    }

    public String generatedTextureKey(String namespace, String path) {
        return TextureResourcePaths.generated(namespace, path).toString();
    }

    public ColorMapAccess getColorMapAccess() {
        return colorMapAccess;
    }

    public ColorMode getColorMode() {
        return session.options().colorMode();
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
        if (spriteKey != null && textureOptions.animationEnabled()) {
            TextureRepository repo = state.getTextureRepository();
            if (!repo.hasAnimation(spriteKey)) {
                String resourceKey = resourceKeyForSprite(spriteKey);
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
        if (textureOptions.atlasMode() == ExportOptions.AtlasMode.INDIVIDUAL && spriteKey != null) {
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
        return session.options().coordinateMode();
    }

    public boolean isVanillaRandomTransformEnabled() {
        return session.options().vanillaRandomTransform();
    }

    public boolean isDiscoveryMode() {
        return state.isDiscoveryMode();
    }

    public void setDiscoveryMode(boolean discoveryMode) {
        state.setDiscoveryMode(discoveryMode);
    }

    @Override
    public void close() {
        session.close();
    }

    private static ExportSessionOptions snapshotOptions() {
        ExportSessionOptions.AtlasMode atlasMode = ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS
            ? ExportSessionOptions.AtlasMode.ATLAS
            : ExportSessionOptions.AtlasMode.INDIVIDUAL;
        return new ExportSessionOptions(
            atlasMode,
            ExportRuntimeConfig.getAtlasSize().getSize(),
            ExportRuntimeConfig.getAtlasPadding(),
            ExportRuntimeConfig.getExportThreadCount(),
            ExportRuntimeConfig.getColorMode(),
            ExportRuntimeConfig.getCoordinateMode(),
            ExportRuntimeConfig.isVanillaRandomTransformEnabled(),
            ExportRuntimeConfig.isAnimationEnabled(),
            ExportRuntimeConfig.isFillCaveEnabled(),
            ExportRuntimeConfig.isPbrDecodeEnabled(),
            ExportRuntimeConfig.isExportDoubleSidedEnabled(),
            ExportRuntimeConfig.isNonsolidCullingEnabled()
        );
    }

    private static ExportOptions textureOptions(ExportSessionOptions options) {
        return new ExportOptions(
            options.atlasMode() == ExportSessionOptions.AtlasMode.ATLAS
                ? ExportOptions.AtlasMode.ATLAS
                : ExportOptions.AtlasMode.INDIVIDUAL,
            options.atlasSize(),
            options.atlasPadding(),
            options.colorMode(),
            options.animation(),
            options.decodePbr(),
            options.collapseDoubleSided()
        );
    }

    private static ClientExecutor clientExecutor(Minecraft mc) {
        return new ClientExecutor() {
            @Override
            public boolean isClientThread() {
                return mc.isSameThread();
            }

            @Override
            public <T> T callBlocking(java.util.concurrent.Callable<T> task) {
                if (isClientThread()) return callUnchecked(task);
                return CompletableFuture.supplyAsync(() -> callUnchecked(task), mc).join();
            }

            private <T> T callUnchecked(java.util.concurrent.Callable<T> task) {
                try {
                    return task.call();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException("Client task failed", exception);
                }
            }
        };
    }

    private static RuntimeDiagnostics runtimeDiagnostics() {
        return event -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("severity", event.severity().name());
            fields.put("message", event.message());
            fields.putAll(event.context());
            VoxelBridgeLogger.probeEvent(event.category(), fields);
        };
    }
}
