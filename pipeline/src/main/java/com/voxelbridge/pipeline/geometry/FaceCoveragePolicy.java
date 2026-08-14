package com.voxelbridge.pipeline.geometry;

import com.voxelbridge.pipeline.contract.OcclusionFacts;

/** Version-neutral policy for conservative face occlusion and overlap tests. */
public final class FaceCoveragePolicy {
    public static final float DEFAULT_EPSILON = 1.0e-3f;

    private FaceCoveragePolicy() {}

    /**
     * Cull only when the runtime has positively established every required fact.
     * Missing or partial coverage is deliberately retained.
     */
    public static boolean shouldCullAgainstNeighbor(
        boolean boundaryFace,
        boolean currentSolid,
        boolean neighborKnown,
        boolean neighborSolid,
        FaceBounds quad,
        FaceBounds neighborCoverage
    ) {
        return shouldCullAgainstNeighbor(
            boundaryFace, currentSolid, neighborKnown, neighborSolid,
            quad, neighborCoverage, DEFAULT_EPSILON);
    }

    /** Policy entry used after an exact-version adapter has normalized Minecraft facts. */
    public static boolean shouldCullAgainstNeighbor(boolean boundaryFace,
                                                    boolean currentSolid,
                                                    OcclusionFacts facts,
                                                    float[] quad,
                                                    float epsilon) {
        if (!boundaryFace || currentSolid || facts == null
                || !facts.neighborLoaded() || !facts.neighborSolid()
                || quad == null || quad.length < 4) {
            return false;
        }
        float[] coverage = facts.coverageRectangles();
        if (facts.fullFaceCoverage()) {
            return contains(0f, 1f, 0f, 1f, quad, epsilon);
        }
        for (int i = 0; i + 3 < coverage.length; i += 4) {
            if (contains(coverage[i], coverage[i + 1], coverage[i + 2], coverage[i + 3],
                    quad, epsilon)) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldCullAgainstNeighbor(
        boolean boundaryFace,
        boolean currentSolid,
        boolean neighborKnown,
        boolean neighborSolid,
        FaceBounds quad,
        FaceBounds neighborCoverage,
        float epsilon
    ) {
        if (!boundaryFace || currentSolid || !neighborKnown || !neighborSolid
            || quad == null || neighborCoverage == null) {
            return false;
        }
        return contains(neighborCoverage, quad, epsilon);
    }

    /** Hot-path overload using borrowed minA,maxA,minB,maxB arrays. */
    public static boolean shouldCullAgainstNeighbor(
        boolean boundaryFace,
        boolean currentSolid,
        boolean neighborKnown,
        boolean neighborSolid,
        float[] quad,
        float[] neighborCoverage,
        float epsilon
    ) {
        if (!boundaryFace || currentSolid || !neighborKnown || !neighborSolid
            || quad == null || neighborCoverage == null || quad.length < 4 || neighborCoverage.length < 4) {
            return false;
        }
        float safeEpsilon = Math.max(0f, epsilon);
        return neighborCoverage[0] - safeEpsilon <= quad[0]
            && neighborCoverage[1] + safeEpsilon >= quad[1]
            && neighborCoverage[2] - safeEpsilon <= quad[2]
            && neighborCoverage[3] + safeEpsilon >= quad[3];
    }

    public static boolean overlaps(FaceBounds left, FaceBounds right) {
        return overlaps(left, right, DEFAULT_EPSILON);
    }

    public static boolean overlaps(FaceBounds left, FaceBounds right, float epsilon) {
        if (left == null || right == null) return false;
        float safeEpsilon = Math.max(0f, epsilon);
        return left.maxA() + safeEpsilon > right.minA()
            && left.minA() - safeEpsilon < right.maxA()
            && left.maxB() + safeEpsilon > right.minB()
            && left.minB() - safeEpsilon < right.maxB();
    }

    /** Hot-path overload using borrowed minA,maxA,minB,maxB arrays. */
    public static boolean overlaps(float[] left, float[] right, float epsilon) {
        if (left == null || right == null || left.length < 4 || right.length < 4) return false;
        float safeEpsilon = Math.max(0f, epsilon);
        return left[1] + safeEpsilon > right[0]
            && left[0] - safeEpsilon < right[1]
            && left[3] + safeEpsilon > right[2]
            && left[2] - safeEpsilon < right[3];
    }

    public static boolean contains(FaceBounds container, FaceBounds inner, float epsilon) {
        if (container == null || inner == null) return false;
        float safeEpsilon = Math.max(0f, epsilon);
        return container.minA() - safeEpsilon <= inner.minA()
            && container.maxA() + safeEpsilon >= inner.maxA()
            && container.minB() - safeEpsilon <= inner.minB()
            && container.maxB() + safeEpsilon >= inner.maxB();
    }

    private static boolean contains(float minA, float maxA, float minB, float maxB,
                                    float[] inner, float epsilon) {
        float safeEpsilon = Math.max(0f, epsilon);
        return minA - safeEpsilon <= inner[0]
            && maxA + safeEpsilon >= inner[1]
            && minB - safeEpsilon <= inner[2]
            && maxB + safeEpsilon >= inner[3];
    }
}
