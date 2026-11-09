package com.voxelbridge.export.exporter.resolve;

import com.voxelbridge.core.ir.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Resolves texture and culling hints from RenderType.
 */
public interface RenderTypeResolver {
    Identifier resolve(RenderType renderType);
    boolean isDoubleSided(RenderType renderType);

    default RenderLayer resolveLayer(RenderType renderType) {
        return RenderLayer.UNKNOWN;
    }
}
