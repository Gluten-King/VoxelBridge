package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.export.util.color.ColorModeHandler;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;

/**
 * Collects quads emitted by the vanilla render pipeline (e.g., fluids) and forwards them to the SceneSink.
 */
final class QuadCollector implements VertexConsumer {
    private final SceneSink sink;
    private final ExportContext ctx;
    private final BlockPos pos;
    private final TextureAtlasSprite[] sprites;
    private final double offsetX, offsetY, offsetZ;
    private final double regionMinX, regionMaxX, regionMinZ, regionMaxZ;
    private final boolean hasRegionBounds;
    private final String materialGroupKey;

    // Coordinate system detection
    private final float[] rawPositions = new float[12];
    private int rawCount = 0;
    private boolean decided = false;
    private boolean needsOffset = false;
    private boolean useChunkOffset = false;
    private int chunkOffsetX = 0;
    private int chunkOffsetY = 0;
    private int chunkOffsetZ = 0;

    // Vertex buffers
    private final float[] positions = new float[12];
    private final float[] uvs = new float[8];
    private final float[] colors = new float[16];
    private int vertexIndex = 0;
    private int quadArgb = 0xFFFFFFFF;
    private boolean quadColorCaptured = false;

    QuadCollector(SceneSink sink, ExportContext ctx, BlockPos pos, TextureAtlasSprite[] sprites,
                  double offsetX, double offsetY, double offsetZ,
                  BlockPos regionMin, BlockPos regionMax, String materialGroupKey) {
        this.sink = sink;
        this.ctx = ctx;
        this.pos = pos;
        this.sprites = sprites;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.materialGroupKey = materialGroupKey;
        if (regionMin != null) {
             this.regionMinX = regionMin.getX() + offsetX;
             this.regionMaxX = regionMax.getX() + offsetX + 1;
             this.regionMinZ = regionMin.getZ() + offsetZ;
             this.regionMaxZ = regionMax.getZ() + offsetZ + 1;
             this.hasRegionBounds = true;
        } else {
             this.regionMinX = 0; this.regionMaxX = 0; this.regionMinZ = 0; this.regionMaxZ = 0; hasRegionBounds = false;
        }
    }

    /**
     * Reset internal state; call between batches to avoid leftover data.
     */
    public void flush() {
        vertexIndex = 0;
        resetQuadState();
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        // Capture raw positions to infer coordinate system
        if (rawCount < 4) {
            rawPositions[rawCount * 3] = x;
            rawPositions[rawCount * 3 + 1] = y;
            rawPositions[rawCount * 3 + 2] = z;
            rawCount++;
        }

        // Decide coordinate system after first quad is captured
        if (!decided && rawCount >= 4) {
            decideCoordinateSystem();
            decided = true;
            recomputePositions();
        }

        // Apply offsets
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

    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        vertexIndex++;
        if (vertexIndex == 4) {
            emitQuad();
            vertexIndex = 0;
        }
        return this;
    }
    
    // 1.21+ compatibility shims
    public void endVertex() {} 
    public void defaultColor(int r, int g, int b, int a) {}
    public void unsetDefaultColor() {}

    private void emitQuad() {
        TextureAtlasSprite sprite = chooseSpriteForQuad();
        if (sprite == null) return;

        float[] normal = GeometryUtil.computeFaceNormal(positions);

        if (hasRegionBounds && isBoundarySideQuad(normal)) {
            resetQuadState();
            return;
        }

        String spriteKey = SpriteKeyResolver.resolve(sprite);
        // Register sprite so animation scan/export (e.g., water_still) is whitelisted
        com.voxelbridge.export.texture.TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
        float[] normalizedUVs = GeometryUtil.normalizeUVs(uvs, sprite);

        // Use ColorModeHandler to prepare colors
        ColorModeHandler.ColorData colorData = ColorModeHandler.prepareColorsWithUV(ctx, quadArgb, normalizedUVs);

        // Send to sink (fluids typically do not have overlays)
        sink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent",
                     positions.clone(), normalizedUVs, colorData.uv1, normal, colorData.colors, true);

        resetQuadState();
    }
    
    private void resetQuadState() {
        rawCount = 0;
        decided = false;
        needsOffset = false;
        useChunkOffset = false;
        quadColorCaptured = false;
        quadArgb = 0xFFFFFFFF;
    }

    private TextureAtlasSprite chooseSpriteForQuad() {
        if (sprites.length == 0) return null;
        if (sprites.length == 1) return sprites[0];
        
        // Heuristic: pick the sprite whose center is closest to the UV centroid
        float centerU = 0, centerV = 0;
        for(int i=0; i<4; i++) { centerU += uvs[i*2]; centerV += uvs[i*2+1]; }
        centerU /= 4; centerV /= 4;
        
        if (isPointInSprite(centerU, centerV, sprites[0])) return sprites[0];
        if (isPointInSprite(centerU, centerV, sprites[1])) return sprites[1];
        
        // Distance fallback
        float d0 = distToCenter(sprites[0], centerU, centerV);
        float d1 = distToCenter(sprites[1], centerU, centerV);
        return d0 <= d1 ? sprites[0] : sprites[1];
    }
    
    private boolean isPointInSprite(float u, float v, TextureAtlasSprite s) {
        return u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1();
    }
    
    private float distToCenter(TextureAtlasSprite s, float u, float v) {
        float cu = (s.getU0()+s.getU1())*0.5f;
        float cv = (s.getV0()+s.getV1())*0.5f;
        return (float)Math.sqrt(Math.pow(u-cu, 2) + Math.pow(v-cv, 2));
    }

    private void decideCoordinateSystem() {
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for(int i=0; i<Math.min(rawCount, 4); i++) {
            float vx = rawPositions[i*3];
            float vy = rawPositions[i*3+1];
            float vz = rawPositions[i*3+2];
            minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
            minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
            minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
        }
        
        // Prefer chunk-local coordinates (0-16) to avoid duplicating Y/Z offsets into world space
        boolean inChunkRange =
            minX >= -0.1f && maxX <= 16.1f &&
            minY >= -0.1f && maxY <= 16.1f &&
            minZ >= -0.1f && maxZ <= 16.1f;

        // Next, treat as single-block local coordinates (0-1)
        boolean inUnit =
            minX >= -0.001f && maxX <= 1.001f &&
            minY >= -0.001f && maxY <= 1.001f &&
            minZ >= -0.001f && maxZ <= 1.001f;

        if (inChunkRange) {
            needsOffset = true; useChunkOffset = true;
            chunkOffsetX = Math.floorDiv(pos.getX(), 16) * 16;
            chunkOffsetY = Math.floorDiv(pos.getY(), 16) * 16;
            chunkOffsetZ = Math.floorDiv(pos.getZ(), 16) * 16;
        } else if (inUnit) {
            needsOffset = true; useChunkOffset = false;
        } else {
            needsOffset = false; useChunkOffset = false;
        }
    }
    private float[] applyOffsets(float x, float y, float z) {
        float wx, wy, wz;
        if (!needsOffset) { wx=x; wy=y; wz=z; }
        else if (useChunkOffset) { wx=chunkOffsetX+x; wy=chunkOffsetY+y; wz=chunkOffsetZ+z; }
        else { wx=pos.getX()+x; wy=pos.getY()+y; wz=pos.getZ()+z; }
        return new float[] { (float)(wx+offsetX), (float)(wy+offsetY), (float)(wz+offsetZ) };
    }

    private void recomputePositions() {
        for(int i=0; i<Math.min(rawCount, 4); i++) {
            float x = rawPositions[i*3], y = rawPositions[i*3+1], z = rawPositions[i*3+2];
            float[] adj = applyOffsets(x, y, z);
            positions[i*3] = adj[0]; positions[i*3+1] = adj[1]; positions[i*3+2] = adj[2];
        }
    }

    private boolean isBoundarySideQuad(float[] normal) {
        // Do not clip boundary faces unless verts truly exceed the selected region (should rarely happen)
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for(int i=0; i<4; i++) {
            minX = Math.min(minX, positions[i*3]);     maxX = Math.max(maxX, positions[i*3]);
            minZ = Math.min(minZ, positions[i*3+2]);   maxZ = Math.max(maxZ, positions[i*3+2]);
        }
        double eps = 1e-3;
        return minX < regionMinX - eps || maxX > regionMaxX + eps ||
               minZ < regionMinZ - eps || maxZ > regionMaxZ + eps;
    }
}
