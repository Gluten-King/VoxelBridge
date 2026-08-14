package com.voxelbridge.pipeline.contract;

@FunctionalInterface
public interface QuadSink {
    /** The supplied view and arrays are borrowed and must not be retained. */
    void accept(QuadInput quad);
}
