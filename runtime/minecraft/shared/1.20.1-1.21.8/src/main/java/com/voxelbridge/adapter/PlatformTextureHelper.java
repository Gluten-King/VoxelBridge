package com.voxelbridge.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.RenderType;
import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import java.util.Collection;
import java.util.Optional;

/**
 * Platform-specific helper for texture operations.
 * Handles NativeImage access, Atlas sprites, and dynamic texture reading.
 */
public interface PlatformTextureHelper {

    /**
     * Gets the ABGR pixel value from a NativeImage.
     * Handles platform-specific mapping if needed (e.g. RGBA vs ABGR).
     */
    int getPixelRgba(NativeImage img, int x, int y);

    /**
     * Gets the original NativeImage from a sprite, if accessible.
     */
    NativeImage getOriginalImage(TextureAtlasSprite sprite);

    /**
     * Gets all sprites registered in a texture atlas.
     */
    Collection<TextureAtlasSprite> getAllSprites(TextureAtlas atlas);

    /**
     * Reads a texture from a ResourceLocation into a NativeImage.
     * Should handle DynamicTexture, HttpTexture, and fallback layers.
     */
    Optional<NativeImage> readTexture(ResourceLocation location);

    /**
     * copy NativeImage from src to dst.
     */
    void copyNativeImage(NativeImage src, NativeImage dst);

    /**
     * Resolves entity-specific textures (e.g. Painting, ItemFrame) that may require
     * platform-specific access to private fields or internal logic.
     * Returns null if not handled by platform helper (fallback to common logic).
     */
    ResolvedTexture resolveEntityTexture(Entity entity, RenderType type);
}
