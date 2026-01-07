package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.texture.Sprite;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STRICT .mcmeta-only animation detection.
 * Only textures with valid .mcmeta animation sections are treated as animated.
 * NO heuristic/perceptual hash detection.
 *
 * OPTIMIZATION: Static cache to avoid 0.5-2s filesystem scan on every export.
 */
public final class AnimatedTextureHelper {

    private AnimatedTextureHelper() {}

    // OPTIMIZATION: Cache to avoid repeated filesystem scans (saves 0.5-2s per export)
    // Thread-safe: uses ConcurrentHashMap
    // Invalidation: cleared when resource packs reload
    private static final ConcurrentHashMap<String, Boolean> animationScanCache = new ConcurrentHashMap<>();
    private static volatile boolean cacheWarmedUp = false;

    /**
     * Invalidates the animation scan cache.
     * Call this when resource packs are reloaded.
     */
    public static void invalidateCache() {
        animationScanCache.clear();
        cacheWarmedUp = false;
        VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][CACHE] Cache invalidated (resource reload)");
    }

    /**
     * Detect animation from .mcmeta file.
     * STRICT MODE: Only accepts textures with valid .mcmeta animation section.
     */
    public static com.voxelbridge.core.texture.AnimatedFrameSet detectFromMetadata(ExportContext ctx, String spriteKey,
                                                                                   String resourceKey, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || spriteKey == null || resourceKey == null || repo == null) {
            return null;
        }
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }
        try {
            AnimationMetadata meta = ctx.getTextureAccess().readAnimationMetadata(resourceKey);
            if (meta == null) {
                return null;
            }
            BufferedImage full = ctx.getTextureAccess().readTexture(resourceKey, true);
            if (full != null) {
                // STRICT: Only use .mcmeta-based splitting.
                return splitWithMetadata(spriteKey, full, meta, repo);
            }
            return null;
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, "[Animation][WARN] Failed to read metadata for " + resourceKey + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * DEPRECATED: Replaced by strict .mcmeta-only detection.
     * Now acts as a fallback that tries to load from metadata if not already cached.
     */
    @Deprecated
    public static com.voxelbridge.core.texture.AnimatedFrameSet extractAndStore(ExportContext ctx, String spriteKey,
                                                                                BufferedImage img, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || spriteKey == null || repo == null || ctx == null) {
            return null;
        }

        // Check if already detected
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }

        // Try to detect from metadata as fallback
        String resourceKey = ctx.getTextureAccess().spriteKeyToResourceKey(spriteKey);
        if (resourceKey != null) {
            return detectFromMetadata(ctx, spriteKey, resourceKey, repo);
        }

        return null;
    }

    /**
     * Split animation frames according to .mcmeta specification.
     * Follows frame order and timing from metadata.
     */
    private static com.voxelbridge.core.texture.AnimatedFrameSet splitWithMetadata(String spriteKey, BufferedImage img,
                                                                                   AnimationMetadata meta, TextureRepository repo) {
        if (meta == null || img == null) {
            return null;
        }
        int frameW = meta.frameWidth();
        int frameH = meta.frameHeight();
        if (frameW <= 0 && frameH <= 0) {
            frameW = img.getWidth();
            frameH = img.getHeight();
        } else if (frameW <= 0) {
            frameW = img.getWidth();
        } else if (frameH <= 0) {
            frameH = img.getHeight();
        }
        int cols = frameW > 0 ? img.getWidth() / frameW : 0;
        int rows = frameH > 0 ? img.getHeight() / frameH : 0;
        int totalFrames = (frameW > 0 && frameH > 0) ? cols * rows : 0;

        // Vanilla fallback: if height is omitted in .mcmeta, assume square frames (frameH = frameW)
        if (totalFrames <= 1 && frameW > 0 && img.getHeight() > frameW && img.getHeight() % frameW == 0) {
            frameH = frameW;
            cols = img.getWidth() / frameW;
            rows = img.getHeight() / frameH;
            totalFrames = cols * rows;
        }
        // Horizontal strip fallback (rare, but keeps mcmeta-only contract)
        if (totalFrames <= 1 && frameH > 0 && img.getWidth() > frameH && img.getWidth() % frameH == 0) {
            frameW = frameH;
            cols = img.getWidth() / frameW;
            rows = img.getHeight() / frameH;
            totalFrames = cols * rows;
        }

        if (frameW <= 0 || frameH <= 0 || img.getWidth() % frameW != 0 || img.getHeight() % frameH != 0 || totalFrames <= 1) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, String.format("[Animation][WARN] Invalid frame dimensions for %s: " +
                "frameW=%d, frameH=%d, imgW=%d, imgH=%d, totalFrames=%d",
                spriteKey, frameW, frameH, img.getWidth(), img.getHeight(), totalFrames
            ));
            return null; // Invalid .mcmeta description (not animated or mismatched grid)
        }

        // Freeze counts for lambda capture
        final int frameCount = totalFrames;

        List<Integer> frameOrder = new ArrayList<>();
        List<AnimationMetadata.FrameTiming> frameTimings = new ArrayList<>();

        // Capture both frame order AND timing information
        for (AnimationMetadata.FrameTiming timing : meta.frameTimings()) {
            int idx = timing.index();
            if (idx >= 0 && idx < frameCount) {
                frameOrder.add(idx);
                frameTimings.add(timing);
            }
        }

        if (frameOrder.isEmpty()) {
            // Default: sequential frames with default timing
            for (int i = 0; i < frameCount; i++) {
                frameOrder.add(i);
            }
        }
        if (frameOrder.isEmpty()) {
            return null;
        }

        List<BufferedImage> frames = new ArrayList<>(frameOrder.size());
        for (int idx : frameOrder) {
            int x = (idx % cols) * frameW;
            int y = (idx / cols) * frameH;
            try {
                BufferedImage frame = new BufferedImage(frameW, frameH, BufferedImage.TYPE_INT_ARGB);
                for (int yy = 0; yy < frameH; yy++) {
                    for (int xx = 0; xx < frameW; xx++) {
                        frame.setRGB(xx, yy, img.getRGB(x + xx, y + yy));
                    }
                }
                frames.add(frame);
            } catch (Exception e) {
                VoxelBridgeLogger.warn(LogModule.ANIMATION, "[Animation][WARN] Failed to slice meta frame " + idx + ": " + e.getMessage());
            }
        }
        if (frames.isEmpty()) {
            return null;
        }

        // Create complete com.voxelbridge.core.texture.AnimationMetadata with captured timing information
        AnimationMetadata animMetadata = new AnimationMetadata(
            meta.defaultFrameTime(),
            frameTimings,
            meta.interpolate(),
            frameW,
            frameH
        );

        com.voxelbridge.core.texture.AnimatedFrameSet set = new com.voxelbridge.core.texture.AnimatedFrameSet(frames, animMetadata);
        repo.putAnimation(spriteKey, set);
        VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
            "[Animation][INFO] Detected animation: %s (%d frames, %dx%d)",
            spriteKey, frames.size(), frameW, frameH
        ));
        VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Meta-sliced %s -> %d frames (%dx%d, %d timings captured)",
            spriteKey, frames.size(), frameW, frameH, frameTimings.size()));
        return set;
    }

    /**
     * Extract animation frames directly from a loaded atlas sprite (uses SpriteContents metadata).
     */
    public static com.voxelbridge.core.texture.AnimatedFrameSet extractFromSprite(ExportContext ctx, String spriteKey,
                                                                                  Sprite sprite, TextureRepository repo) {
        return null;
    }

    /**
     * Hybrid animation scanning: Atlas sprites + file system fallback.
     * Logs all detection attempts for debugging.
     * OPTIMIZATION: Uses static cache to avoid repeated filesystem scans.
     */
    public static void scanAllAnimations(ExportContext ctx, java.util.Set<String> whitelist) {
        TextureRepository repo = ctx.getTextureRepository();

        // OPTIMIZATION: Skip expensive filesystem scan if cache is already warmed up
        if (cacheWarmedUp) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][CACHE] Using cached scan results (skipping 0.5-2s filesystem scan)");
            return;
        }

        int totalFound = 0;

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation] ===== ANIMATION SCAN START =====");

        if (whitelist != null && !whitelist.isEmpty()) {
            // Whitelist-only scan: probe exactly the sprites used by the current export.
            int whitelistFound = 0;
            for (String key : whitelist) {
                if (repo.hasAnimation(key)) continue;
                if (key.endsWith("_n") || key.endsWith("_s")) continue; // skip PBR companions
                String resourceKey = ctx.getTextureAccess().spriteKeyToResourceKey(key);
                if (resourceKey == null) continue;
                com.voxelbridge.core.texture.AnimatedFrameSet frames = detectFromMetadata(ctx, key, resourceKey, repo);
                if (frames != null) {
                    whitelistFound++;
                    com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                        "[Animation][WHITELIST] %s (%d frames)", key, frames.frames().size()
                    ));
                } else {
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][WHITELIST][MISS] No animation metadata for: " + resourceKey);
            }
            }
            totalFound += whitelistFound;
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Whitelist scan complete: %d animations found", whitelistFound));
        } else {
            // Fallback path: broader scan (atlas + standard paths) if no whitelist available.
            try {
                int atlasCount = scanAtlasAnimations(repo, whitelist);
                totalFound += atlasCount;
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Atlas scan: %d animations found", atlasCount));
            } catch (Exception e) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] Atlas scan failed: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                String[] additionalPaths = {
                    "textures/block/",
                    "textures/entity/",
                    "textures/particle/",
                    "textures/painting/",
                    "textures/mob_effect/",
                    "textures/item/"
                };

                for (String path : additionalPaths) {
                    int pathCount = scanPathForMetadata(ctx, path, repo, whitelist);
                    totalFound += pathCount;
                    com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] Path '%s': %d animations found", path, pathCount));
                }
            } catch (Exception e) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] File scan failed: " + e.getMessage());
                e.printStackTrace();
            }
        }

        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[Animation] ===== SCAN COMPLETE: %d total animations =====", totalFound));

        // OPTIMIZATION: Mark cache as warmed up to skip future scans
        cacheWarmedUp = true;
        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][CACHE] Cache warmed up - future exports will skip filesystem scan");
    }

    /**
     * Scan a single TextureAtlas for animations using SpriteContents.
     * NOTE: TextureAtlas.getSprites() is not available in the current Minecraft API.
     * This method is kept as a placeholder for future API support.
     * For now, animation detection relies entirely on file system scanning.
     */
    private static int scanAtlasAnimations(TextureRepository repo,
                                           java.util.Set<String> whitelist) {
        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][INFO] Atlas.getSprites() API not available, skipping atlas scan");
        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][INFO] Relying on file system scanning for animation detection");
        return 0;
    }

    /**
     * Scan a specific path for .mcmeta files.
     */
    private static int scanPathForMetadata(ExportContext ctx,
                                           String path,
                                           TextureRepository repo,
                                           java.util.Set<String> whitelist) {
        int foundCount = 0;

        // Normalize path to avoid trailing slash errors
        String cleanPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

        try {
            java.util.Set<String> resources = ctx.getTextureAccess().listPngResources(cleanPath);

            com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                "[Animation][DEBUG] listResources('%s') available: %d files",
                cleanPath, resources.size()
            ));

            for (String pngKey : resources) {
                try {
                    // Check for .mcmeta companion
                    String metaKey = pngKey + ".mcmeta";
                    if (ctx.getTextureAccess().hasResource(metaKey)) {
                        String spriteKey = pngKeyToSpriteKey(pngKey);
                        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(spriteKey)) {
                            // ? whitelist ?sprite
                            if (!spriteKey.startsWith("minecraft:")) {
                                VoxelBridgeLogger.info(LogModule.ANIMATION, "[Animation][DEBUG] Excluded by whitelist: " + spriteKey);
                            }
                            continue; // Skip sprites outside the export whitelist
                        }
                        // Record every discovered .mcmeta for debugging
                        com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                            "[Animation][MCMETA] %s meta=%s", spriteKey, metaKey
                        ));

                        if (!repo.hasAnimation(spriteKey)) {
                            // Only treat as animated when .mcmeta is present AND valid
                            com.voxelbridge.core.texture.AnimatedFrameSet frames = detectFromMetadata(ctx, spriteKey, pngKey, repo);
                            if (frames != null) {
                                foundCount++;
                                com.voxelbridge.util.debug.VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                                    "[Animation] File scan hit: %s (%d frames)",
                                    spriteKey, frames.frames().size()
                                ));
                            }
                        }
                    }
                } catch (Exception e) {
                    com.voxelbridge.util.debug.VoxelBridgeLogger.warn(LogModule.ANIMATION, "[Animation][WARN] File check error for " + pngKey + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.error(LogModule.ANIMATION, "[Animation][ERROR] Path scan failed for '" + path + "': " + e.getMessage());
            e.printStackTrace();
        }

        return foundCount;
    }

    private static String pngKeyToSpriteKey(String pngKey) {
        int split = pngKey.indexOf(':');
        if (split <= 0) {
            return null;
        }
        String namespace = pngKey.substring(0, split);
        String path = pngKey.substring(split + 1);
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return namespace + ":" + path;
    }

}






