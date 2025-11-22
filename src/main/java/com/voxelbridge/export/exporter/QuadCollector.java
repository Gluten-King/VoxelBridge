package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;

/**
 * VertexConsumer implementation that collects quads from Minecraft's
 * rendering pipeline and forwards them to a SceneSink.
 *
 * This is used primarily for fluid rendering where we intercept
 * the vanilla liquid renderer output.
 */
final class QuadCollector implements VertexConsumer {
    private final SceneSink sink;
    private final ExportContext ctx;
    private final BlockPos pos;
    private final TextureAtlasSprite[] sprites;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final double regionMinX;
    private final double regionMaxX;
    private final double regionMinZ;
    private final double regionMaxZ;
    private final boolean hasRegionBounds;

    // Coordinate system detection (fluids sometimes emit local coords)
    private final float[] rawPositions = new float[12];
    private int rawCount = 0;
    private boolean decided = false;
    private boolean needsOffset = false;
    private boolean useChunkOffset = false;
    private int chunkOffsetX = 0;
    private int chunkOffsetY = 0;
    private int chunkOffsetZ = 0;

    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final float[] colors = new float[16];
    private int quadArgb = 0xFFFFFFFF;
    private boolean quadColorCaptured = false;
    private int vertexIndex = 0;

    QuadCollector(SceneSink sink, ExportContext ctx, BlockPos pos, TextureAtlasSprite[] sprites,
                  double offsetX, double offsetY, double offsetZ,
                  BlockPos regionMin, BlockPos regionMax) {
        this.sink = sink;
        this.ctx = ctx;
        this.pos = pos;
        this.sprites = sprites;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        if (regionMin != null && regionMax != null) {
            this.regionMinX = regionMin.getX() + offsetX;
            this.regionMaxX = regionMax.getX() + offsetX + 1; // max edge is inclusive of block, add 1
            this.regionMinZ = regionMin.getZ() + offsetZ;
            this.regionMaxZ = regionMax.getZ() + offsetZ + 1;
            this.hasRegionBounds = true;
        } else {
            this.regionMinX = this.regionMaxX = this.regionMinZ = this.regionMaxZ = 0;
            this.hasRegionBounds = false;
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        // Store raw coords for the current quad
        if (rawCount < rawPositions.length) {
            rawPositions[rawCount * 3] = x;
            rawPositions[rawCount * 3 + 1] = y;
            rawPositions[rawCount * 3 + 2] = z;
            rawCount++;
        }

        // Decide coordinate system once we have the first quad's 4 vertices
        if (!decided && rawCount >= 4) {
            decideCoordinateSystem();
            decided = true;
            // Re-apply offsets for any vertices already captured in this quad
            recomputePositions();
        }

        // Apply the chosen offset strategy
        float[] adjusted = applyOffsets(x, y, z);
        positions[vertexIndex * 3] = adjusted[0];
        positions[vertexIndex * 3 + 1] = adjusted[1];
        positions[vertexIndex * 3 + 2] = adjusted[2];
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        colors[vertexIndex * 4] = r / 255f;
        colors[vertexIndex * 4 + 1] = g / 255f;
        colors[vertexIndex * 4 + 2] = b / 255f;
        colors[vertexIndex * 4 + 3] = a / 255f;
        if (!quadColorCaptured) {
            quadArgb = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            quadColorCaptured = true;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        uvs[vertexIndex * 2] = u;
        uvs[vertexIndex * 2 + 1] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        // Lightmap - not used for export
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        // Overlay - not used for export
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        // Normal will be computed from positions
        // This is typically the last method called per vertex in Minecraft's rendering
        vertexIndex++;

        // When we have 4 vertices, we have a complete quad
        if (vertexIndex == 4) {
            emitQuad();
            vertexIndex = 0;
        }

        return this;
    }

    public void endVertex() {
        // This method may not be called by Minecraft's fluid renderer
    }

    public void defaultColor(int r, int g, int b, int a) {
        // Not used for export
    }

    public void unsetDefaultColor() {
        // Not used for export
    }

    private void emitQuad() {
        // Determine which sprite to use (still vs flowing) based on UVs
        TextureAtlasSprite sprite = chooseSpriteForQuad();
        if (sprite == null) {
            return;
        }

        // Drop fluid side faces that sit exactly on the region boundary to avoid "water walls".
        if (hasRegionBounds && isBoundarySideQuad()) {
            resetQuadState();
            return;
        }

        String spriteKey = SpriteKeyResolver.resolve(sprite);

        // Normalize UVs to sprite coordinates
        float[] normalizedUVs = new float[8];
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float du = u1 - u0;
        float dv = v1 - v0;
        if (du == 0) du = 1f;
        if (dv == 0) dv = 1f;

        for (int i = 0; i < 4; i++) {
            float u = uvs[i * 2];
            float v = uvs[i * 2 + 1];

            // Convert from atlas UV to normalized [0,1] for this sprite (no wrap)
            float su = (u - u0) / du;
            float sv = (v - v0) / dv;
            su = clamp01(su);
            sv = clamp01(sv);

            normalizedUVs[i * 2] = su;
            normalizedUVs[i * 2 + 1] = sv;
        }

        // Compute face normal
        float[] normal = computeFaceNormal(positions);

        // Always use colormap for biome colors
        float[] lut = ColorMapManager.remapColorUV(ctx, quadArgb); // u0, v0, u1, v1
        u0 = lut[0];
        v0 = lut[1];
        u1 = lut[2];
        v1 = lut[3];
        du = u1 - u0;
        dv = v1 - v0;

        float[] uv1 = new float[8];
        for (int i = 0; i < 4; i++) {
            // Remap the original normalized UVs into the small color box
            // This preserves the general shape of the UV mapping, just scaled down.
            uv1[i * 2] = u0 + normalizedUVs[i * 2] * du;
            uv1[i * 2 + 1] = v0 + normalizedUVs[i * 2 + 1] * dv;
        }
        float[] linearColors = whiteColor();

        // Send to sink
        sink.addQuad(spriteKey, positions.clone(), normalizedUVs, uv1, null, null, normal, linearColors, true);

        // Reset raw buffer state for next quad
        rawCount = 0;
        decided = false;
        needsOffset = false;
        useChunkOffset = false;
        quadColorCaptured = false;
        quadArgb = 0xFFFFFFFF;
    }

    private void resetQuadState() {
        rawCount = 0;
        decided = false;
        needsOffset = false;
        useChunkOffset = false;
        quadColorCaptured = false;
        quadArgb = 0xFFFFFFFF;
    }

    private boolean isBoundarySideQuad() {
        // Compute bounds of the emitted quad in world space (with offsets already applied)
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double x = positions[i * 3];
            double z = positions[i * 3 + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        double eps = 1e-3;
        boolean onMinX = Math.abs(minX - regionMinX) < eps && Math.abs(maxX - regionMinX) < eps;
        boolean onMaxX = Math.abs(minX - regionMaxX) < eps && Math.abs(maxX - regionMaxX) < eps;
        boolean onMinZ = Math.abs(minZ - regionMinZ) < eps && Math.abs(maxZ - regionMinZ) < eps;
        boolean onMaxZ = Math.abs(minZ - regionMaxZ) < eps && Math.abs(maxZ - regionMaxZ) < eps;

        // Only drop vertical side faces; allow top surfaces to remain.
        // The fluid renderer emits vertical faces aligned to X or Z when flowing against "air".
        return onMinX || onMaxX || onMinZ || onMaxZ;
    }

    private float[] computeFaceNormal(float[] pos) {
        float ax = pos[3] - pos[0];
        float ay = pos[4] - pos[1];
        float az = pos[5] - pos[2];
        float bx = pos[6] - pos[0];
        float by = pos[7] - pos[1];
        float bz = pos[8] - pos[2];

        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0f) {
            return new float[]{0, 1, 0};
        }
        return new float[]{nx / len, ny / len, nz / len};
    }

    private float[] whiteColor() {
        return new float[]{
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f,
                1f, 1f, 1f, 1f
        };
    }

    private float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private TextureAtlasSprite chooseSpriteForQuad() {
        if (sprites.length == 0) {
            return null;
        }
        if (sprites.length == 1) {
            return sprites[0];
        }
        TextureAtlasSprite still = sprites[0];
        TextureAtlasSprite flowing = sprites[1];

        float centerU = 0f;
        float centerV = 0f;
        int count = 0;
        for (int i = 0; i < 4; i++) {
            centerU += uvs[i * 2];
            centerV += uvs[i * 2 + 1];
            count++;
        }
        if (count == 0) {
            return still;
        }
        centerU /= count;
        centerV /= count;

        if (isPointInSprite(centerU, centerV, still, 0.02f)) {
            return still;
        }
        if (isPointInSprite(centerU, centerV, flowing, 0.02f)) {
            return flowing;
        }

        float distStill = distanceToSpriteCenter(still, centerU, centerV);
        float distFlow = distanceToSpriteCenter(flowing, centerU, centerV);
        return distStill <= distFlow ? still : flowing;
    }

    private boolean isPointInSprite(float u, float v, TextureAtlasSprite sprite, float eps) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        return u >= u0 - eps && u <= u1 + eps && v >= v0 - eps && v <= v1 + eps;
    }

    private float distanceToSpriteCenter(TextureAtlasSprite sprite, float u, float v) {
        float centerU = (sprite.getU0() + sprite.getU1()) * 0.5f;
        float centerV = (sprite.getV0() + sprite.getV1()) * 0.5f;
        float du = u - centerU;
        float dv = v - centerV;
        return (float) Math.sqrt(du * du + dv * dv);
    }

    private void decideCoordinateSystem() {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < Math.min(rawCount, 4); i++) {
            float vx = rawPositions[i * 3];
            float vy = rawPositions[i * 3 + 1];
            float vz = rawPositions[i * 3 + 2];
            minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
            minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
            minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
        }

        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkY = Math.floorDiv(pos.getY(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        int chunkBaseX = chunkX * 16;
        int chunkBaseY = chunkY * 16;
        int chunkBaseZ = chunkZ * 16;
        int localX = Math.floorMod(pos.getX(), 16);
        int localY = Math.floorMod(pos.getY(), 16);
        int localZ = Math.floorMod(pos.getZ(), 16);

        boolean inUnitRange = minX >= -0.001f && maxX <= 1.001f
                && minY >= -0.001f && maxY <= 1.001f
                && minZ >= -0.001f && maxZ <= 1.001f;
        if (inUnitRange) {
            needsOffset = true;
            useChunkOffset = false;
            return;
        }

        boolean xInChunkRange = minX >= -0.1f && maxX <= 16.1f;
        boolean yInChunkRange = minY >= -0.1f && maxY <= 16.1f;
        boolean zInChunkRange = minZ >= -0.1f && maxZ <= 16.1f;
        boolean xMatchesLocal = Math.abs(minX - (float) localX) < 1.5f;
        boolean yMatchesLocal = Math.abs(minY - (float) localY) < 1.5f;
        boolean zMatchesLocal = Math.abs(minZ - (float) localZ) < 1.5f;
        boolean looksLikeChunkCoords = xInChunkRange && yInChunkRange && zInChunkRange
                && xMatchesLocal && yMatchesLocal && zMatchesLocal;
        if (looksLikeChunkCoords) {
            needsOffset = true;
            useChunkOffset = true;
            chunkOffsetX = chunkBaseX;
            chunkOffsetY = chunkBaseY;
            chunkOffsetZ = chunkBaseZ;
            return;
        }

        needsOffset = false;
        useChunkOffset = false;
    }

    private float[] applyOffsets(float x, float y, float z) {
        float wx, wy, wz;
        if (!needsOffset) {
            wx = x; wy = y; wz = z;
        } else if (useChunkOffset) {
            wx = chunkOffsetX + x;
            wy = chunkOffsetY + y;
            wz = chunkOffsetZ + z;
        } else {
            wx = pos.getX() + x;
            wy = pos.getY() + y;
            wz = pos.getZ() + z;
        }
        return new float[] {
                (float) (wx + offsetX),
                (float) (wy + offsetY),
                (float) (wz + offsetZ)
        };
    }

    private void recomputePositions() {
        int verts = Math.min(rawCount, 4);
        for (int i = 0; i < verts; i++) {
            float x = rawPositions[i * 3];
            float y = rawPositions[i * 3 + 1];
            float z = rawPositions[i * 3 + 2];
            float[] adjusted = applyOffsets(x, y, z);
            positions[i * 3] = adjusted[0];
            positions[i * 3 + 1] = adjusted[1];
            positions[i * 3 + 2] = adjusted[2];
        }
    }

    /**
     * Flush any remaining incomplete quad data.
     * This is called after renderLiquid completes.
     */
    public void flush() {
        // Note: Incomplete quad data indicates a potential issue with vertex data collection
        rawCount = 0;
        decided = false;
        needsOffset = false;
        useChunkOffset = false;
    }
}
