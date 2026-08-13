package com.voxelbridge.verification;

import com.voxelbridge.core.util.geometry.QuadGeometryKey;

/** Dependency-free regression tests for double-sided quad collapse identity. */
public final class QuadGeometryKeyTest {

    private QuadGeometryKeyTest() {}

    public static void main(String[] args) {
        float[] rectangle = {
            -1f, -1f, 0f,
             1f, -1f, 0f,
             1f,  1f, 0f,
            -1f,  1f, 0f
        };
        float[] rectangleUv = {
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        };
        float[] reversedRectangle = {
            -1f,  1f, 0f,
             1f,  1f, 0f,
             1f, -1f, 0f,
            -1f, -1f, 0f
        };
        float[] reversedUv = {
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        };

        QuadGeometryKey original = QuadGeometryKey.of(42, 0xFFFFFFFF, rectangle, rectangleUv);
        QuadGeometryKey oppositeWinding = QuadGeometryKey.of(
            42, 0xFFFFFFFF, reversedRectangle, reversedUv
        );
        require(original.equals(oppositeWinding),
            "The same textured plane with opposite winding must collapse");
        require(original.hashCode() == oppositeWinding.hashCode(),
            "Equal geometry keys must have equal hash codes");

        // Same center and the same projected AABB as the rectangle, but a different outline.
        float[] diamond = {
            -1f,  0f, 0f,
             0f, -1f, 0f,
             1f,  0f, 0f,
             0f,  1f, 0f
        };
        require(!original.equals(QuadGeometryKey.of(42, 0xFFFFFFFF, diamond, rectangleUv)),
            "Different outlines sharing center and bounds must not collapse");

        float[] parallelPlane = rectangle.clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            parallelPlane[vertex * 3 + 2] += 0.375f;
        }
        require(!original.equals(QuadGeometryKey.of(
                42, 0xFFFFFFFF, parallelPlane, rectangleUv
            )),
            "Faces sharing projected bounds but lying on different planes must not collapse");

        float[] atlasUvDifference = rectangleUv.clone();
        atlasUvDifference[0] += 0.0002f;
        require(!original.equals(QuadGeometryKey.of(
                42, 0xFFFFFFFF, rectangle, atlasUvDifference
            )),
            "Sub-milliscale atlas UV differences must not collapse");

        require(!original.equals(QuadGeometryKey.of(42, 0xFF00FF00, rectangle, rectangleUv)),
            "Different tint assignments must not collapse");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
