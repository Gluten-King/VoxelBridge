package com.voxelbridge.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.voxelbridge.export.texture.DynamicTextureUtil;
import com.voxelbridge.export.texture.DynamicTextureUtil.DynamicTextureKind;
import com.voxelbridge.export.texture.DynamicTextureUtil.NormalizedTexture;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.Dumpable;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Template for the dynamic texture resolution chain.
 * <p>
 * All path normalization, Dumpable loading, and ResourceManager fallback live here.
 * Platform/version subclasses only implement the four abstract atomic operations
 * where Minecraft's internal API actually differs across versions.
 */
public abstract class AbstractDynamicTextureReader {

    private final String versionTag;

    protected AbstractDynamicTextureReader(String versionTag) {
        this.versionTag = versionTag;
    }

    /**
     * Complete dynamic texture resolution chain. Not overridable.
     */
    public final Optional<NativeImage> readTexture(Identifier location) {
        if (location == null) {
            return Optional.empty();
        }

        NormalizedTexture norm = DynamicTextureUtil.normalizeAll(location);
        location = norm.location();
        if (location == null) {
            return Optional.empty();
        }

        if (RenderSystem.isOnRenderThread()) {
            if (norm.kind() == DynamicTextureKind.MAP) {
                preheatMapTexture(location);
            }

            Optional<NativeImage> mapImage = loadMapPixels(location);
            if (mapImage.isPresent()) {
                return Optional.of(copyNativeImage(mapImage.get()));
            }

            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);

            NativeImage dynPixels = getDynamicTexturePixels(texture);
            if (dynPixels != null) {
                debug("DynamicTexture pixels loaded: " + location);
                return Optional.of(copyNativeImage(dynPixels));
            }

            Optional<NativeImage> dumpableImage = loadDumpableTexture(texture, location);
            if (dumpableImage.isPresent()) {
                NativeImage ni = dumpableImage.get();
                debug("Dumpable texture loaded: " + location
                    + " size=" + ni.getWidth() + "x" + ni.getHeight()
                    + " type=" + texture.getClass().getSimpleName());
                return Optional.of(copyNativeImage(ni));
            }

            Optional<NativeImage> httpImage = loadHttpTexture(texture);
            if (httpImage.isPresent()) {
                debug("HttpTexture loaded: " + location);
                return httpImage;
            }
        }

        return loadFromResourceManager(location);
    }

    // =========================================================================
    // Abstract methods — platform/version subclasses must implement these
    // =========================================================================

    /**
     * Triggers a map texture update so that the DynamicTexture has valid pixel data.
     * <ul>
     *   <li>1.20.1 / 1.21.1: {@code mc.gameRenderer.getMapRenderer().update(id, data)}</li>
     *   <li>1.21.4+: {@code mc.getMapTextureManager().update(id, data)}</li>
     * </ul>
     */
    protected abstract void preheatMapTexture(Identifier location);

    /**
     * Reads map pixels directly from the map renderer via mixin accessors.
     * Returns {@link Optional#empty()} on versions where this is not available.
     */
    protected abstract Optional<NativeImage> loadMapPixels(Identifier location);

    /**
     * Extracts NativeImage pixels from an AbstractTexture if it is a DynamicTexture.
     * Returns {@code null} if not applicable.
     */
    protected abstract NativeImage getDynamicTexturePixels(AbstractTexture texture);

    /**
     * Loads texture data from an HttpTexture's cached file.
     * Returns {@link Optional#empty()} on 1.21.4+ where HttpTexture was removed.
     */
    protected abstract Optional<NativeImage> loadHttpTexture(AbstractTexture texture);

    // =========================================================================
    // Common implementations shared by all platforms/versions
    // =========================================================================

    /**
     * Loads a texture that implements the {@link Dumpable} interface (font atlas pages, etc.)
     * by dumping to a temporary directory and reading back the PNG file.
     */
    protected Optional<NativeImage> loadDumpableTexture(AbstractTexture texture, Identifier location) {
        if (!(texture instanceof Dumpable dumpable) || location == null) {
            return Optional.empty();
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("voxelbridge-fontdump-");
            dumpable.dumpContents(location, tempDir);
            Adapters.getPlatformRenderHelper().awaitTextureDump();
            try (Stream<Path> stream = Files.walk(tempDir)) {
                Optional<Path> png = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .findFirst();
                if (png.isEmpty()) {
                    return Optional.empty();
                }
                try (InputStream in = Files.newInputStream(png.get())) {
                    return Optional.of(NativeImage.read(in));
                }
            }
        } catch (Exception exception) {
            VoxelBridgeLogger.warn(LogModule.DYNAMIC_MAP,
                "[DynamicTextureReader/" + versionTag + "] Failed to dump runtime texture "
                    + location + ": " + exception.getMessage());
            return Optional.empty();
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    protected Optional<NativeImage> loadFromResourceManager(Identifier location) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent()) {
                try (var is = resource.get().open()) {
                    return Optional.of(NativeImage.read(is));
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    protected NativeImage copyNativeImage(NativeImage src) {
        NativeImage dst = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        dst.copyFrom(src);
        return dst;
    }

    protected void debug(String message) {
        if (VoxelBridgeLogger.isDebugEnabled(LogModule.DYNAMIC_MAP)) {
            VoxelBridgeLogger.debug(LogModule.DYNAMIC_MAP,
                "[DynamicTextureReader/" + versionTag + "] " + message);
        }
    }

    private static void cleanupTempDir(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
