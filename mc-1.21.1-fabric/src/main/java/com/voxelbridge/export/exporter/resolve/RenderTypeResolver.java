package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/**
 * Resolves texture and culling hints from RenderType.
 */
public interface RenderTypeResolver {
    Identifier resolve(RenderLayer renderType);
    boolean isDoubleSided(RenderLayer renderType);
}
