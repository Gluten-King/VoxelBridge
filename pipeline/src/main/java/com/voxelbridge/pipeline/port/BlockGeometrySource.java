package com.voxelbridge.pipeline.port;

import com.voxelbridge.pipeline.contract.BlockSample;
import com.voxelbridge.pipeline.contract.QuadSink;

public interface BlockGeometrySource {
    BlockGeometrySource EMPTY = (block, sink) -> {};

    void emitBlockQuads(BlockSample block, QuadSink sink);
}
