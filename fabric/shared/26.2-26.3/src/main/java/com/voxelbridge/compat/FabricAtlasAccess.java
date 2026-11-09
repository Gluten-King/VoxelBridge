package com.voxelbridge.compat;

import com.voxelbridge.mixin.TextureAtlasAccessor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Fabric-specific atlas access using Mixin accessor.
 */
public final class FabricAtlasAccess {

    private FabricAtlasAccess() {
    }

    /**
     * Gets all sprites from a TextureAtlas using Mixin accessor.
     */
    public static Collection<TextureAtlasSprite> getAllSprites(TextureAtlas atlas) {
        if (atlas == null) {
            return Collections.emptyList();
        }
        try {
            Map<?, TextureAtlasSprite> map = ((TextureAtlasAccessor) atlas).voxelbridge$getTexturesByName();
            return map != null ? map.values() : Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
