package com.voxelbridge.modhandler.ctm;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import com.voxelbridge.export.texture.SpriteKeyResolver;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects CTM (Connected Textures Mod) models and overlays.
 * Reflection-based Continuity probing has been removed; overlay detection now relies on geometry elsewhere.
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
    public static CtmOverlayInfo resolveCtmOverlay(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, String spriteKey, Object model) {
        // Legacy API kept; Continuity reflection removed; overlay detection is handled elsewhere via geometry.
        return null;
    }
}
