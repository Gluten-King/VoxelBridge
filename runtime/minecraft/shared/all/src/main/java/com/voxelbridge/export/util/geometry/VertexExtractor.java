package com.voxelbridge.export.util.geometry;

import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.core.util.geometry.GeometryUtil;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Unified vertex extraction utility for Block/Fluid/BlockEntity geometry.
 * Centralizes vertex data extraction logic to avoid code duplication.
 */
public final class VertexExtractor {

    private VertexExtractor() {
        // Utility class - prevent instantiation
    }

    /**
     * Vertex data extracted from a quad.
     */
    public record VertexData(
        float[] positions,  // 12 floats: 4 vertices * (x, y, z)
        float[] uvs,        // 8 floats: 4 vertices * (u, v)
        float[] normal,     // 3 floats: (nx, ny, nz)
        int[] colors        // 4 ints: ARGB per vertex
    ) {}

    /**
     * Extracts vertex data from a quad with world transformation.
     *
     * @param quad the quad data
     * @param pos block position
     * @param sprite texture sprite for UV normalization
     * @param offsetX world X offset (for centering)
     * @param offsetY world Y offset (for centering)
     * @param offsetZ world Z offset (for centering)
     * @param randomOffset vanilla random offset (grass, fern, etc.)
     * @return extracted vertex data
     */
    public static VertexData extractFromQuad(
        QuadData quad,
        BlockPos pos,
        TextureAtlasSprite sprite,
        double offsetX,
        double offsetY,
        double offsetZ,
        Vec3 randomOffset
    ) {
        float[] positions = new float[12];
        float[] uv = new float[8];
        int[] colors = new int[4];

        int[] verts = quad.vertices();
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        int spriteWidth = sprite.contents().width();
        int spriteHeight = sprite.contents().height();
        v1 = GeometryUtil.computeAnimatedV1(v0, v1, spriteWidth, spriteHeight);

        float du = GeometryUtil.computeUvDelta(u0, u1);
        float dv = GeometryUtil.computeUvDelta(v0, v1);

        // Extract vertices (4 vertices per quad)
        for (int i = 0; i < 4; i++) {
            int base = i * 8;  // DefaultVertexFormat.BLOCK stride
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base + 1]);
            float vz = Float.intBitsToFloat(verts[base + 2]);
            int argb = verts[base + 3];
            float uu = Float.intBitsToFloat(verts[base + 4]);
            float vv = Float.intBitsToFloat(verts[base + 5]);

            // Transform to world coordinates with double precision
            double worldX = pos.getX() + vx + offsetX + (randomOffset != null ? randomOffset.x : 0);
            double worldY = pos.getY() + vy + offsetY + (randomOffset != null ? randomOffset.y : 0);
            double worldZ = pos.getZ() + vz + offsetZ + (randomOffset != null ? randomOffset.z : 0);

            positions[i * 3] = (float) worldX;
            positions[i * 3 + 1] = (float) worldY;
            positions[i * 3 + 2] = (float) worldZ;

            GeometryUtil.normalizeUv(uu, vv, u0, v0, du, dv, uv, i * 2);

            colors[i] = argb;
        }

        // Compute face normal
        float[] normal = GeometryUtil.computeFaceNormal(positions);

        return new VertexData(positions, uv, normal, colors);
    }

    /**
     * Extracts world positions and normalized UVs into provided arrays (no allocation).
     * Optional colors array may be provided to capture per-vertex color values.
     */
    public static void extractPositionsUv(
        QuadData quad,
        BlockPos pos,
        TextureAtlasSprite sprite,
        double offsetX,
        double offsetY,
        double offsetZ,
        Vec3 randomOffset,
        float[] positionsOut,
        float[] uvOut,
        int[] colorsOut
    ) {
        if (positionsOut == null || positionsOut.length < 12) return;
        if (uvOut == null || uvOut.length < 8) return;

        int[] verts = quad.vertices();
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        int spriteWidth = sprite.contents().width();
        int spriteHeight = sprite.contents().height();
        v1 = GeometryUtil.computeAnimatedV1(v0, v1, spriteWidth, spriteHeight);

        float du = GeometryUtil.computeUvDelta(u0, u1);
        float dv = GeometryUtil.computeUvDelta(v0, v1);

        // Extract vertices (4 vertices per quad)
        for (int i = 0; i < 4; i++) {
            int base = i * 8;  // DefaultVertexFormat.BLOCK stride
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base + 1]);
            float vz = Float.intBitsToFloat(verts[base + 2]);
            int argb = verts[base + 3];
            float uu = Float.intBitsToFloat(verts[base + 4]);
            float vv = Float.intBitsToFloat(verts[base + 5]);

            // Transform to world coordinates with double precision
            double worldX = pos.getX() + vx + offsetX + (randomOffset != null ? randomOffset.x : 0);
            double worldY = pos.getY() + vy + offsetY + (randomOffset != null ? randomOffset.y : 0);
            double worldZ = pos.getZ() + vz + offsetZ + (randomOffset != null ? randomOffset.z : 0);

            positionsOut[i * 3] = (float) worldX;
            positionsOut[i * 3 + 1] = (float) worldY;
            positionsOut[i * 3 + 2] = (float) worldZ;

            GeometryUtil.normalizeUv(uu, vv, u0, v0, du, dv, uvOut, i * 2);

            if (colorsOut != null && colorsOut.length >= 4) {
                colorsOut[i] = argb;
            }
        }
    }

    /**
     * Extracts local vertex positions (0-1 range) from a quad.
     * Used for overlay offset calculations to avoid float precision loss.
     *
     * @param quad the quad data
     * @return local positions (12 floats)
     */
    public static float[] extractLocalPositions(QuadData quad) {
        float[] localPos = new float[12];
        int[] verts = quad.vertices();

        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            localPos[i * 3] = Float.intBitsToFloat(verts[base]);
            localPos[i * 3 + 1] = Float.intBitsToFloat(verts[base + 1]);
            localPos[i * 3 + 2] = Float.intBitsToFloat(verts[base + 2]);
        }

        return localPos;
    }

    /**
     * Converts local positions to world positions with offsets.
     *
     * @param localPos local positions (0-1 range)
     * @param pos block position
     * @param offsetX world X offset
     * @param offsetY world Y offset
     * @param offsetZ world Z offset
     * @param randomOffset vanilla random offset
     * @return world positions (12 floats)
     */
    public static float[] localToWorld(
        float[] localPos,
        BlockPos pos,
        double offsetX,
        double offsetY,
        double offsetZ,
        Vec3 randomOffset
    ) {
        float randX = randomOffset != null ? (float) randomOffset.x : 0f;
        float randY = randomOffset != null ? (float) randomOffset.y : 0f;
        float randZ = randomOffset != null ? (float) randomOffset.z : 0f;
        return GeometryUtil.localToWorld(localPos, pos.getX(), pos.getY(), pos.getZ(),
            offsetX, offsetY, offsetZ, randX, randY, randZ);
    }
}
