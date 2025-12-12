package com.voxelbridge.export.texture;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.util.ExportLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * STRICT .mcmeta-only animation detection.
 * Only textures with valid .mcmeta animation sections are treated as animated.
 * NO heuristic/perceptual hash detection.
 */
public final class AnimatedTextureHelper {

    private AnimatedTextureHelper() {}

    /**
     * Detect animation from .mcmeta file.
     * STRICT MODE: Only accepts textures with valid .mcmeta animation section.
     */
    public static AnimatedFrameSet detectFromMetadata(String spriteKey, ResourceLocation png, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || spriteKey == null || png == null || repo == null) {
            return null;
        }
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            var resOpt = rm.getResource(png);
            if (resOpt.isEmpty()) {
                return null;
            }
            var res = resOpt.get();
            var metaOpt = res.metadata().getSection(AnimationMetadataSection.SERIALIZER);
            AnimationMetadataSection meta = metaOpt.orElse(null);
            if (meta == null) {
                return null; // No .mcmeta animation section - NOT an animation
            }
            BufferedImage full = TextureLoader.readTexture(png, true);
            if (full != null) {
                // STRICT: Only use .mcmeta-based splitting
                return splitWithMetadata(spriteKey, full, meta, repo);
            }
            return null;
        } catch (Exception e) {
            ExportLogger.log("[Animation][WARN] Failed to read metadata for " + png + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * DEPRECATED: Replaced by strict .mcmeta-only detection.
     * Now acts as a fallback that tries to load from metadata if not already cached.
     */
    @Deprecated
    public static AnimatedFrameSet extractAndStore(String spriteKey, BufferedImage img, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || spriteKey == null || repo == null) {
            return null;
        }

        // Check if already detected
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }

        // Try to detect from metadata as fallback
        ResourceLocation pngLoc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
        if (pngLoc != null) {
            return detectFromMetadata(spriteKey, pngLoc, repo);
        }

        return null;
    }

    /**
     * Split animation frames according to .mcmeta specification.
     * Follows frame order and timing from metadata.
     */
    private static AnimatedFrameSet splitWithMetadata(String spriteKey, BufferedImage img, AnimationMetadataSection meta, TextureRepository repo) {
        if (meta == null || img == null) {
            return null;
        }
        var size = meta.calculateFrameSize(img.getWidth(), img.getHeight());
        int frameW = size.width();
        int frameH = size.height();
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
            return null; // Invalid .mcmeta description (not animated or mismatched grid)
        }

        // Freeze counts for lambda capture
        final int frameCount = totalFrames;

        List<Integer> frameOrder = new ArrayList<>();
        List<AnimationMetadata.FrameTiming> frameTimings = new ArrayList<>();

        // Capture both frame order AND timing information
        meta.forEachFrame((idx, time) -> {
            if (idx >= 0 && idx < frameCount) {
                frameOrder.add(idx);
                frameTimings.add(new AnimationMetadata.FrameTiming(idx, time));
            }
        });

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
                ExportLogger.log("[Animation][WARN] Failed to slice meta frame " + idx + ": " + e.getMessage());
            }
        }
        if (frames.isEmpty()) {
            return null;
        }

        // Create complete AnimationMetadata with captured timing information
        boolean interpolate = false;
        try {
            interpolate = meta.isInterpolatedFrames();
        } catch (NoSuchMethodError e) {
            ExportLogger.log("[Animation][DEBUG] AnimationMetadataSection.isInterpolatedFrames() not available, using false");
        }

        AnimationMetadata animMetadata = new AnimationMetadata(
            meta.getDefaultFrameTime(),
            frameTimings,
            interpolate,
            frameW,
            frameH
        );

        AnimatedFrameSet set = new AnimatedFrameSet(frames, animMetadata);
        repo.putAnimation(spriteKey, set);
        ExportLogger.log(String.format("[Animation] Meta-sliced %s -> %d frames (%dx%d, %d timings captured)",
            spriteKey, frames.size(), frameW, frameH, frameTimings.size()));
        return set;
    }

    /**
     * Extract animation frames directly from a loaded atlas sprite (uses SpriteContents metadata).
     */
    public static AnimatedFrameSet extractFromSprite(String spriteKey, TextureAtlasSprite sprite, TextureRepository repo) {
        if (!ExportRuntimeConfig.isAnimationEnabled() || sprite == null || spriteKey == null || repo == null) {
            return null;
        }
        if (repo.hasAnimation(spriteKey)) {
            return repo.getAnimation(spriteKey);
        }

        try {
            var contents = sprite.contents();
            var metaOpt = contents.metadata().getSection(AnimationMetadataSection.SERIALIZER);
            AnimationMetadataSection meta = metaOpt.orElse(null);
            if (meta == null) {
                return null; // No animation metadata
            }

            // Read full texture from sprite
            BufferedImage full = TextureLoader.readTexture(contents.name(), true);
            if (full != null) {
                AnimatedFrameSet frames = splitWithMetadata(spriteKey, full, meta, repo);
                if (frames != null) {
                    return frames;
                }
            }
            return null;
        } catch (Exception e) {
            ExportLogger.log("[Animation][WARN] Failed to extract from sprite: " + spriteKey + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Hybrid animation scanning: Atlas sprites + file system fallback.
     * Logs all detection attempts for debugging.
     */
    public static void scanAllAnimations(com.voxelbridge.export.ExportContext ctx, java.util.Set<String> whitelist) {
        TextureRepository repo = ctx.getTextureRepository();
        int totalFound = 0;

        com.voxelbridge.util.ExportLogger.logAnimation("[Animation] ===== ANIMATION SCAN START =====");

        // Step 1: Scan TextureAtlas (fast, 95% coverage)
        try {
            net.minecraft.client.renderer.texture.TextureAtlas blockAtlas =
                ctx.getMc().getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            int atlasCount = scanAtlasAnimations(blockAtlas, repo, whitelist);
            totalFound += atlasCount;
            com.voxelbridge.util.ExportLogger.logAnimation(String.format("[Animation] Atlas scan: %d animations found", atlasCount));
        } catch (Exception e) {
            com.voxelbridge.util.ExportLogger.logAnimation("[Animation][ERROR] Atlas scan failed: " + e.getMessage());
            e.printStackTrace();
        }

        // Step 2: File system fallback for non-atlas textures
        try {
            net.minecraft.server.packs.resources.ResourceManager rm =
                net.minecraft.client.Minecraft.getInstance().getResourceManager();
            String[] additionalPaths = {
                "textures/block/",      // Block textures (campfire, lava, water, etc.)
                "textures/entity/",     // Entity textures
                "textures/particle/",   // Particle effects
                "textures/painting/",   // Paintings
                "textures/mob_effect/", // Potion effects
                "textures/item/"        // Item textures (clock, compass)
            };

            for (String path : additionalPaths) {
                int pathCount = scanPathForMetadata(rm, "minecraft", path, repo, whitelist);
                totalFound += pathCount;
                com.voxelbridge.util.ExportLogger.logAnimation(String.format("[Animation] Path '%s': %d animations found", path, pathCount));
            }
        } catch (Exception e) {
            com.voxelbridge.util.ExportLogger.logAnimation("[Animation][ERROR] File scan failed: " + e.getMessage());
            e.printStackTrace();
        }

        com.voxelbridge.util.ExportLogger.logAnimation(String.format("[Animation] ===== SCAN COMPLETE: %d total animations =====", totalFound));
    }

    /**
     * Scan a single TextureAtlas for animations using SpriteContents.
     * NOTE: TextureAtlas.getSprites() is not available in the current Minecraft API.
     * This method is kept as a placeholder for future API support.
     * For now, animation detection relies entirely on file system scanning.
     */
    private static int scanAtlasAnimations(net.minecraft.client.renderer.texture.TextureAtlas atlas,
                                           TextureRepository repo,
                                           java.util.Set<String> whitelist) {
        com.voxelbridge.util.ExportLogger.logAnimation("[Animation][INFO] Atlas.getSprites() API not available, skipping atlas scan");
        com.voxelbridge.util.ExportLogger.logAnimation("[Animation][INFO] Relying on file system scanning for animation detection");
        return 0;
    }

    /**
     * Scan a specific path for .mcmeta files.
     */
    private static int scanPathForMetadata(net.minecraft.server.packs.resources.ResourceManager rm,
                                           String namespace,
                                           String path,
                                           TextureRepository repo,
                                           java.util.Set<String> whitelist) {
        int foundCount = 0;

        // Normalize path to avoid trailing slash errors
        String cleanPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;

        try {
            // List resources in the path - correct API signature
            java.util.Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> resources =
                rm.listResources(cleanPath, loc -> loc.getPath().endsWith(".png"));

            com.voxelbridge.util.ExportLogger.logAnimation(String.format(
                "[Animation][DEBUG] listResources('%s') available: %d files",
                cleanPath, resources.size()
            ));

            for (ResourceLocation pngLoc : resources.keySet()) {
                try {
                    // Only process files from the specified namespace
                    if (!namespace.equals(pngLoc.getNamespace())) {
                        continue;
                    }

                    // Check for .mcmeta companion
                    ResourceLocation metaLoc = ResourceLocation.fromNamespaceAndPath(
                        pngLoc.getNamespace(),
                        pngLoc.getPath() + ".mcmeta"
                    );

                    if (rm.getResource(metaLoc).isPresent()) {
                        String spriteKey = pngLocToSpriteKey(pngLoc);
                        if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(spriteKey)) {
                            continue; // Skip sprites outside the export whitelist
                        }
                        // Record every discovered .mcmeta for debugging
                        com.voxelbridge.util.ExportLogger.logAnimation(String.format(
                            "[Animation][MCMETA] %s meta=%s", spriteKey, metaLoc
                        ));

                        if (!repo.hasAnimation(spriteKey)) {
                            // Only treat as animated when .mcmeta is present AND valid
                            AnimatedFrameSet frames = detectFromMetadata(spriteKey, pngLoc, repo);
                            if (frames != null) {
                                foundCount++;
                                com.voxelbridge.util.ExportLogger.logAnimation(String.format(
                                    "[Animation] File scan hit: %s (%d frames)",
                                    spriteKey, frames.frames().size()
                                ));
                            }
                        }
                    }
                } catch (Exception e) {
                    com.voxelbridge.util.ExportLogger.logAnimation("[Animation][WARN] File check error for " + pngLoc + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            com.voxelbridge.util.ExportLogger.logAnimation("[Animation][ERROR] Path scan failed for '" + path + "': " + e.getMessage());
            e.printStackTrace();
        }

        return foundCount;
    }

    private static String pngLocToSpriteKey(ResourceLocation pngLoc) {
        String path = pngLoc.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return pngLoc.getNamespace() + ":" + path;
    }
}
