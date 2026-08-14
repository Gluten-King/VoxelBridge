package com.voxelbridge.platform.render;

import com.voxelbridge.export.exporter.resolve.RenderTypeResolver;
import com.voxelbridge.adapter.Adapters;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves texture resource locations from RenderType instances.
 * Delegates to PlatformRenderHelper.
 */
public final class RenderTypeTextureResolver implements RenderTypeResolver {

    public static final RenderTypeTextureResolver INSTANCE = new RenderTypeTextureResolver();

    private RenderTypeTextureResolver() {
    }

    @Override
    public ResourceLocation resolve(RenderType renderType) {
        return Adapters.getPlatformRenderHelper().getRenderTypeTexture(renderType);
    }

    @Override
    public boolean isDoubleSided(RenderType renderType) {
        return Adapters.getPlatformRenderHelper().isRenderTypeDoubleSided(renderType);
    }
}
