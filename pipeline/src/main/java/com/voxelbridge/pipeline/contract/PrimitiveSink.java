package com.voxelbridge.pipeline.contract;

@FunctionalInterface
public interface PrimitiveSink {
    /** The supplied arrays are borrowed and must not be retained. */
    void accept(CapturedPrimitive primitive);
}
