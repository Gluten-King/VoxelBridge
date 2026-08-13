package com.voxelbridge.core.util.geometry;

import java.util.Arrays;

/**
 * Order-independent identity for a textured quad.
 *
 * <p>The key retains every quantized position and its corresponding UV. This is
 * intentionally stricter than comparing a quad centroid and projected bounds:
 * different shapes can share both of those coarse properties (for example,
 * narrow fence parts) and must not be collapsed as duplicate double-sided
 * faces.</p>
 */
public final class QuadGeometryKey {

    private static final float POSITION_QUANT = 10_000f;
    private static final float UV_QUANT = 1_000_000f;
    private static final int COMPONENTS_PER_VERTEX = 5;
    private static final int VERTEX_COUNT = 4;

    private final int spriteHash;
    private final int tintArgb;
    private final boolean hasUv;
    private final int[] vertices;
    private final int hashCode;

    private QuadGeometryKey(int spriteHash, int tintArgb, boolean hasUv, int[] vertices) {
        this.spriteHash = spriteHash;
        this.tintArgb = tintArgb;
        this.hasUv = hasUv;
        this.vertices = vertices;

        int hash = 31 * spriteHash + tintArgb;
        hash = 31 * hash + Boolean.hashCode(hasUv);
        this.hashCode = 31 * hash + Arrays.hashCode(vertices);
    }

    public static QuadGeometryKey of(int spriteHash, int tintArgb, float[] positions, float[] uvs) {
        if (positions == null || positions.length < VERTEX_COUNT * 3) {
            throw new IllegalArgumentException("A quad requires four 3D positions");
        }

        boolean hasUv = uvs != null && uvs.length >= VERTEX_COUNT * 2;
        int[] source = new int[VERTEX_COUNT * COMPONENTS_PER_VERTEX];
        for (int vertex = 0; vertex < VERTEX_COUNT; vertex++) {
            int sourceOffset = vertex * COMPONENTS_PER_VERTEX;
            int positionOffset = vertex * 3;
            source[sourceOffset] = Math.round(positions[positionOffset] * POSITION_QUANT);
            source[sourceOffset + 1] = Math.round(positions[positionOffset + 1] * POSITION_QUANT);
            source[sourceOffset + 2] = Math.round(positions[positionOffset + 2] * POSITION_QUANT);
            if (hasUv) {
                int uvOffset = vertex * 2;
                source[sourceOffset + 3] = Math.round(uvs[uvOffset] * UV_QUANT);
                source[sourceOffset + 4] = Math.round(uvs[uvOffset + 1] * UV_QUANT);
            }
        }

        int[] order = {0, 1, 2, 3};
        for (int index = 1; index < order.length; index++) {
            int candidate = order[index];
            int cursor = index - 1;
            while (cursor >= 0 && compareVertex(source, order[cursor], candidate) > 0) {
                order[cursor + 1] = order[cursor];
                cursor--;
            }
            order[cursor + 1] = candidate;
        }

        int[] canonical = new int[source.length];
        for (int vertex = 0; vertex < VERTEX_COUNT; vertex++) {
            System.arraycopy(
                source,
                order[vertex] * COMPONENTS_PER_VERTEX,
                canonical,
                vertex * COMPONENTS_PER_VERTEX,
                COMPONENTS_PER_VERTEX
            );
        }
        return new QuadGeometryKey(spriteHash, tintArgb, hasUv, canonical);
    }

    private static int compareVertex(int[] vertices, int leftVertex, int rightVertex) {
        int leftOffset = leftVertex * COMPONENTS_PER_VERTEX;
        int rightOffset = rightVertex * COMPONENTS_PER_VERTEX;
        for (int component = 0; component < COMPONENTS_PER_VERTEX; component++) {
            int comparison = Integer.compare(
                vertices[leftOffset + component],
                vertices[rightOffset + component]
            );
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QuadGeometryKey key)) {
            return false;
        }
        return spriteHash == key.spriteHash
            && tintArgb == key.tintArgb
            && hasUv == key.hasUv
            && Arrays.equals(vertices, key.vertices);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
