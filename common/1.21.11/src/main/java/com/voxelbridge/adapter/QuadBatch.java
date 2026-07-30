package com.voxelbridge.adapter;

import com.voxelbridge.export.quad.QuadData;

import java.util.Collections;
import java.util.List;

/**
 * Quad batch with source metadata for downstream routing.
 */
public record QuadBatch(List<QuadData> quads, QuadSource source) {
    public QuadBatch {
        quads = quads != null ? quads : Collections.emptyList();
        source = source != null ? source : QuadSource.UNKNOWN;
    }
}
