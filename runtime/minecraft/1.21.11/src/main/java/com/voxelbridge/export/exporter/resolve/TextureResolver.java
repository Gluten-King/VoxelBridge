package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Resolves textures for rendered primitives based on RenderType and source object.
 */
public interface TextureResolver<T> {
    ResolvedTexture resolve(T source, RenderType renderType);
}
