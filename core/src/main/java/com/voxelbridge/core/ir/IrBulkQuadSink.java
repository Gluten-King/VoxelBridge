package com.voxelbridge.core.ir;

import java.util.List;

/**
 * Optional bulk-quad ingestion for high-throughput IR sinks.
 * quadFlags should be encoded using {@link IrFlags}.
 */
public interface IrBulkQuadSink {
    void addBatch(String materialGroupKey,
                  List<String> spriteKeys,
                  List<String> overlaySpriteKeys,
                  List<QuadSemantic> semantics,
                  float[] flatPositions,
                  float[] flatUv0s,
                  float[] flatUv1s,
                  float[] flatLightUvs,
                  float[] flatMidBlocks,
                  float[] flatNormals,
                  float[] flatColors,
                  int[] quadFlags);
}
