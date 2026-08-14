package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves texture and culling hints from RenderType.
 */
public interface RenderTypeResolver {
    ResourceLocation resolve(RenderType renderType);
    boolean isDoubleSided(RenderType renderType);
}
