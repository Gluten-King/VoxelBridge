package com.voxelbridge.pipeline.port;

import com.voxelbridge.pipeline.contract.PrimitiveSink;
import com.voxelbridge.pipeline.contract.Region3i;

public interface SpecialRenderSource {
    SpecialRenderSource EMPTY = (region, sink) -> {};

    void emitSpecialPrimitives(Region3i region, PrimitiveSink sink);
}
