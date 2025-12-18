package com.voxelbridge.modhandler.ctm;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import com.voxelbridge.export.texture.SpriteKeyResolver;

import java.util.*;

/**
 * Detects CTM (Connected Textures Mod) models and overlays.
 * Uses reflection to inspect Continuity mod's internal structures.
 */
public final class CtmDetector {

    private CtmDetector() {}

    /**
     * CTM overlay information resolved from properties or reflection.
     */
    public record CtmOverlayInfo(
        boolean isOverlay,
        String baseMaterialKey,
        String propertiesPath,
        Integer tileIndex
    ) {}

    // Cache CTM properties-based overlay detection by sprite path
    private static final Map<String, CtmOverlayInfo> ctmOverlayCache = new HashMap<>();

    // Reflection-based cache for Continuity overlay processors (sprite -> overlay info)
    private static final Map<String, CtmOverlayInfo> continuityOverlayCache = new HashMap<>();
    private static boolean continuityOverlayScanned = false;
    private static boolean continuityOverlayRetryAllowed = true;

    /**
     * Detects if a model uses CTM/connected textures.
     *
     * @param model the baked model
     * @param quads list of quads from the model
     * @return true if this is a CTM model
     */
    public static boolean isCTMModel(BakedModel model, List<BakedQuad> quads) {
        if (!(model instanceof FabricBakedModel fbm)) {
            return false;
        }
        if (fbm.isVanillaAdapter()) {
            return false;
        }

        String className = model.getClass().getName().toLowerCase();
        boolean classHints = className.contains("continuity") || className.contains("ctm") || className.contains("connected");

        // Check sprite diversity (CTM uses multiple sprite variants)
        Set<String> uniqueSprites = new HashSet<>();
        boolean ctmPathHint = false;

        for (BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) continue;
            String spriteKey = SpriteKeyResolver.resolve(quad.getSprite());
            if (spriteKey.contains("_overlay")) continue;

            String baseSprite = spriteKey.replaceAll("_\\d+$", "");
            uniqueSprites.add(baseSprite);

            // Path-based hints for CTM
            String lower = spriteKey.toLowerCase(Locale.ROOT);
            if (lower.contains("/ctm/") || lower.contains("ctm/") || lower.contains("_ctm") || lower.contains("/connected/")) {
                ctmPathHint = true;
            }
        }

        // Loosen detection: if the model class hints CTM OR sprites have CTM-like paths
        if (classHints && (uniqueSprites.size() >= 1)) return true;
        if (uniqueSprites.size() > 1) return true;
        return ctmPathHint;
    }

    /**
     * Resolves CTM overlay information for a sprite.
     *
     * @param sprite the texture sprite
     * @param spriteKey resolved sprite key
     * @param model the baked model
     * @return CTM overlay info, or null if not an overlay
     */
    public static CtmOverlayInfo resolveCtmOverlay(TextureAtlasSprite sprite, String spriteKey, Object model) {
        if (sprite == null) return null;

        ResourceLocation name = sprite.contents().name();
        String cacheKey = name.toString();

        if (ctmOverlayCache.containsKey(cacheKey)) {
            return ctmOverlayCache.get(cacheKey);
        }

        CtmOverlayInfo result = null;

        // First, try Continuity reflection (QuadProcessors -> overlay processors)
        CtmOverlayInfo reflectedProcessor = tryReflectContinuityProcessors(name);
        if (reflectedProcessor != null) {
            ctmOverlayCache.put(cacheKey, reflectedProcessor);
            return reflectedProcessor;
        }

        // Try to reflect properties from CtmBakedModel
        CtmOverlayInfo reflected = ContinuityReflector.tryReflectCtm(model, name, spriteKey);
        if (reflected != null) {
            ctmOverlayCache.put(cacheKey, reflected);
            return reflected;
        }

        // Heuristic fallback for Continuity reserved sprites
        if (result == null && name.getPath().contains("continuity_reserved")) {
            int tileIdx = parseTileIndex(name);
            result = new CtmOverlayInfo(true, null, null, tileIdx >= 0 ? tileIdx : null);
        }

        ctmOverlayCache.put(cacheKey, result);
        return result;
    }

    /**
     * Scans Continuity's QuadProcessors for overlay processors.
     */
    @SuppressWarnings("unchecked")
    private static CtmOverlayInfo tryReflectContinuityProcessors(ResourceLocation spriteName) {
        // Build the lookup map once
        if (continuityOverlayScanned && continuityOverlayCache.isEmpty() && continuityOverlayRetryAllowed) {
            continuityOverlayRetryAllowed = false;
            continuityOverlayScanned = false;
        }

        if (!continuityOverlayScanned) {
            continuityOverlayScanned = true;
            try {
                Class<?> qpClass = Class.forName("me.pepperbell.continuity.client.model.QuadProcessors");
                var holdersField = qpClass.getDeclaredField("processorHolders");
                holdersField.setAccessible(true);
                Object holdersObj = holdersField.get(null);

                if (holdersObj instanceof Object[] holdersArr) {
                    for (Object holder : holdersArr) {
                        if (holder == null) continue;

                        Object processor = null;
                        try {
                            var m = holder.getClass().getMethod("processor");
                            processor = m.invoke(holder);
                        } catch (Throwable ignored) {}

                        if (processor == null) continue;

                        Class<?> pCls = processor.getClass();
                        String name = pCls.getName().toLowerCase(Locale.ROOT);
                        if (!name.contains("overlay")) continue;

                        // Try to read fields from StandardOverlayQuadProcessor
                        Object[] sprites = null;
                        Set<?> connectTiles = null;
                        Set<?> matchTiles = null;

                        try {
                            var f = pCls.getDeclaredField("sprites");
                            f.setAccessible(true);
                            sprites = (Object[]) f.get(processor);
                        } catch (Throwable ignored) {}

                        try {
                            var f = pCls.getDeclaredField("connectTilesSet");
                            f.setAccessible(true);
                            connectTiles = (Set<?>) f.get(processor);
                        } catch (Throwable ignored) {}

                        try {
                            var f = pCls.getDeclaredField("matchTilesSet");
                            f.setAccessible(true);
                            matchTiles = (Set<?>) f.get(processor);
                        } catch (Throwable ignored) {}

                        String baseMaterialKey = null;
                        baseMaterialKey = firstIdentifier(connectTiles);
                        if (baseMaterialKey == null) baseMaterialKey = firstIdentifier(matchTiles);

                        if (sprites != null) {
                            for (Object s : sprites) {
                                String key = spriteNameFromObject(s);
                                if (key == null) continue;
                                continuityOverlayCache.put(key, new CtmOverlayInfo(true, baseMaterialKey, "reflect:processor:" + pCls.getName(), null));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // Failed to scan Continuity processors
            }
        }

        if (continuityOverlayCache.isEmpty()) return null;
        return continuityOverlayCache.get(spriteName.toString());
    }

    private static int parseTileIndex(ResourceLocation spriteName) {
        String file = spriteName.getPath();
        int idx = file.lastIndexOf('/');
        if (idx >= 0) file = file.substring(idx + 1);
        try {
            return Integer.parseInt(file.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String firstIdentifier(Set<?> set) {
        if (set == null || set.isEmpty()) return null;
        Object first = set.iterator().next();
        if (first == null) return null;
        String s = first.toString();
        // Normalize missing namespace
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        return s;
    }

    private static String spriteNameFromObject(Object spriteObj) {
        if (spriteObj == null) return null;
        try {
            var getContents = spriteObj.getClass().getMethod("getContents");
            Object contents = getContents.invoke(spriteObj);
            if (contents != null) {
                try {
                    var getId = contents.getClass().getMethod("getId");
                    Object id = getId.invoke(contents);
                    return (id != null) ? id.toString() : null;
                } catch (NoSuchMethodException ignored) {
                    try {
                        var nameField = contents.getClass().getDeclaredField("id");
                        nameField.setAccessible(true);
                        Object id = nameField.get(contents);
                        return (id != null) ? id.toString() : null;
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            var contentsField = spriteObj.getClass().getDeclaredField("contents");
            contentsField.setAccessible(true);
            Object contents = contentsField.get(spriteObj);
            if (contents != null) {
                var getId = contents.getClass().getMethod("getId");
                Object id = getId.invoke(contents);
                return (id != null) ? id.toString() : null;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Clears all caches. Call when reloading resources.
     */
    public static void clearCaches() {
        ctmOverlayCache.clear();
        continuityOverlayCache.clear();
        continuityOverlayScanned = false;
        continuityOverlayRetryAllowed = true;
    }
}
