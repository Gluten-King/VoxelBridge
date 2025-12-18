package com.voxelbridge.export.util.geometry;

import net.minecraft.client.renderer.block.model.BakedQuad;
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
        int[] colors        // 4 ints: ABGR per vertex
    ) {}

    /**
     * Extracts vertex data from a BakedQuad with world transformation.
     *
     * @param quad the baked quad
     * @param pos block position
     * @param sprite texture sprite for UV normalization
     * @param offsetX world X offset (for centering)
     * @param offsetY world Y offset (for centering)
     * @param offsetZ world Z offset (for centering)
     * @param randomOffset vanilla random offset (grass, fern, etc.)
     * @return extracted vertex data
     */
    public static VertexData extractFromQuad(
        BakedQuad quad,
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

        int[] verts = quad.getVertices();
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();

        // Detect animated texture (height > width) and adjust v1 to first frame
        int spriteWidth = sprite.contents().width();
        int spriteHeight = sprite.contents().height();
        if (spriteHeight > spriteWidth) {
            int frameCount = spriteHeight / spriteWidth;
            float frameRatio = 1.0f / frameCount;
            v1 = v0 + (v1 - v0) * frameRatio;
        }

        float du = u1 - u0;
        if (du == 0) du = 1f;
        float dv = v1 - v0;
        if (dv == 0) dv = 1f;

        // Extract vertices (4 vertices per quad)
        for (int i = 0; i < 4; i++) {
            int base = i * 8;  // DefaultVertexFormat.BLOCK stride
            float vx = Float.intBitsToFloat(verts[base]);
            float vy = Float.intBitsToFloat(verts[base + 1]);
            float vz = Float.intBitsToFloat(verts[base + 2]);
            int abgr = verts[base + 3];
            float uu = Float.intBitsToFloat(verts[base + 4]);
            float vv = Float.intBitsToFloat(verts[base + 5]);

            // Transform to world coordinates with double precision
            double worldX = pos.getX() + vx + offsetX + (randomOffset != null ? randomOffset.x : 0);
            double worldY = pos.getY() + vy + offsetY + (randomOffset != null ? randomOffset.y : 0);
            double worldZ = pos.getZ() + vz + offsetZ + (randomOffset != null ? randomOffset.z : 0);

            positions[i * 3] = (float) worldX;
            positions[i * 3 + 1] = (float) worldY;
            positions[i * 3 + 2] = (float) worldZ;

            // Normalize UVs to [0, 1] range
            uv[i * 2] = (uu - u0) / du;
            uv[i * 2 + 1] = (vv - v0) / dv;

            colors[i] = abgr;
        }

        // Compute face normal
        float[] normal = GeometryUtil.computeFaceNormal(positions);

        return new VertexData(positions, uv, normal, colors);
    }

    /**
     * Extracts local vertex positions (0-1 range) from a BakedQuad.
     * Used for overlay offset calculations to avoid float precision loss.
     *
     * @param quad the baked quad
     * @return local positions (12 floats)
     */
    public static float[] extractLocalPositions(BakedQuad quad) {
        float[] localPos = new float[12];
        int[] verts = quad.getVertices();

        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            localPos[i * 3] = Float.intBitsToFloat(verts[base]);
            localPos[i * 3 + 1] = Float.intBitsToFloat(verts[base + 1]);
            localPos[i * 3 + 2] = Float.intBitsToFloat(verts[base + 2]);
        }

        return localPos;
    }

    /**
     * Applies overlay z-offset in local coordinate system to prevent z-fighting.
     * Operates on local coordinates (0-1 range) for better float precision.
     *
     * @param localPositions local vertex positions (modified in-place)
     * @param overlayIndex overlay layer index (0-based, 0 = first overlay)
     */
    public static void applyOverlayOffset(float[] localPositions, int overlayIndex) {
        if (localPositions == null || localPositions.length < 12) return;

        // Constants
        final float OVERLAY_ZFIGHT_OFFSET = 3e-4f;
        final float LOCAL_CENTER = 0.5f;

        // Calculate quad center in local coordinate system
        float cx = 0f, cy = 0f, cz = 0f;
        for (int i = 0; i < 4; i++) {
            cx += localPositions[i * 3];
            cy += localPositions[i * 3 + 1];
            cz += localPositions[i * 3 + 2];
        }
        cx *= 0.25f;
        cy *= 0.25f;
        cz *= 0.25f;

        // Direction from block center to quad center
        float dx = cx - LOCAL_CENTER;
        float dy = cy - LOCAL_CENTER;
        float dz = cz - LOCAL_CENTER;

        // Find dominant axis (which face this quad is on)
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        float adz = Math.abs(dz);

        float nx, ny, nz;
        if (adx >= ady && adx >= adz) {
            // X-axis face (East/West)
            nx = dx > 0 ? 1f : -1f;
            ny = 0f;
            nz = 0f;
        } else if (ady >= adx && ady >= adz) {
            // Y-axis face (Top/Bottom)
            nx = 0f;
            ny = dy > 0 ? 1f : -1f;
            nz = 0f;
        } else {
            // Z-axis face (North/South)
            nx = 0f;
            ny = 0f;
            nz = dz > 0 ? 1f : -1f;
        }

        // Apply progressive offset based on overlay index
        float offsetMultiplier = (overlayIndex + 1);
        float offset = OVERLAY_ZFIGHT_OFFSET * offsetMultiplier;

        // Apply offset to all vertices
        for (int i = 0; i < 4; i++) {
            localPositions[i * 3] += nx * offset;
            localPositions[i * 3 + 1] += ny * offset;
            localPositions[i * 3 + 2] += nz * offset;
        }
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
        float[] worldPos = new float[12];
        for (int i = 0; i < 4; i++) {
            double worldX = pos.getX() + localPos[i * 3] + offsetX + (randomOffset != null ? randomOffset.x : 0);
            double worldY = pos.getY() + localPos[i * 3 + 1] + offsetY + (randomOffset != null ? randomOffset.y : 0);
            double worldZ = pos.getZ() + localPos[i * 3 + 2] + offsetZ + (randomOffset != null ? randomOffset.z : 0);

            worldPos[i * 3] = (float) worldX;
            worldPos[i * 3 + 1] = (float) worldY;
            worldPos[i * 3 + 2] = (float) worldZ;
        }
        return worldPos;
    }
}
