package com.voxelbridge.pipeline.contract;

/**
 * Runtime-observed visibility and neighbor coverage for one candidate face.
 * coverageRectangles contains zero or more minA,maxA,minB,maxB tuples. It is a
 * hot-path borrowed array and must not be retained by a sink.
 */
public record OcclusionFacts(
    boolean vanillaVisible,
    boolean neighborLoaded,
    boolean neighborSolid,
    boolean fullFaceCoverage,
    float[] coverageRectangles
) {
    private static final float[] EMPTY = new float[0];

    public OcclusionFacts {
        coverageRectangles = coverageRectangles == null ? EMPTY : coverageRectangles;
        if ((coverageRectangles.length & 3) != 0) {
            throw new IllegalArgumentException("Coverage rectangles must contain groups of four floats");
        }
    }

    public static OcclusionFacts visible() {
        return new OcclusionFacts(true, true, false, false, EMPTY);
    }

    public static OcclusionFacts unknown() {
        return new OcclusionFacts(true, false, false, false, EMPTY);
    }
}
