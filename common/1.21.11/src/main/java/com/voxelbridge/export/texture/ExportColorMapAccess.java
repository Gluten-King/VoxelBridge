package com.voxelbridge.export.texture;

import com.voxelbridge.core.util.color.ColorMapAccess;
import com.voxelbridge.core.util.color.ColorMapUv;
import com.voxelbridge.export.ExportContext;

/**
 * Export-side adapter for core color map access.
 */
public final class ExportColorMapAccess implements ColorMapAccess {
    private final ExportContext ctx;

    public ExportColorMapAccess(ExportContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public ColorMapUv registerColor(int argb) {
        var placement = ColorMapManager.registerColor(ctx.state(), argb);
        return new ColorMapUv(placement.u0(), placement.v0(), placement.u1(), placement.v1());
    }
}
