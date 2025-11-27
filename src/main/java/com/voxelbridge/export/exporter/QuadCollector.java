package com.voxelbridge.export.exporter;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.SpriteKeyResolver;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;

/**
 * 收集来自 Minecraft 渲染管线（如流体渲染）的 Quad 并转发给 SceneSink。
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

    // 坐标系检测相关
    private final float[] rawPositions = new float[12];
    private int rawCount = 0;
    private boolean decided = false;
    private boolean needsOffset = false;
    private boolean useChunkOffset = false;
    private int chunkOffsetX = 0;
    private int chunkOffsetY = 0;
    private int chunkOffsetZ = 0;

    // 顶点缓存
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
             this.regionMinX=0; this.regionMaxX=0; this.regionMinZ=0; this.regionMaxZ=0; hasRegionBounds=false;
        }
    }

    // === 新增/必需的方法 ===
    /**
     * 重置收集器状态。由外部调用以确保没有残留数据。
     */
    public void flush() {
        vertexIndex = 0;
        resetQuadState();
    }
    // ======================

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        // 存储原始坐标用于判定坐标系
        if (rawCount < 4) {
            rawPositions[rawCount * 3] = x;
            rawPositions[rawCount * 3 + 1] = y;
            rawPositions[rawCount * 3 + 2] = z;
            rawCount++;
        }

        // 收集够4个顶点后判定坐标系
        if (!decided && rawCount >= 4) {
            decideCoordinateSystem();
            decided = true;
            recomputePositions();
        }

        // 应用偏移
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
    
    // 兼容 1.21+ 接口
    public void endVertex() {} 
    public void defaultColor(int r, int g, int b, int a) {}
    public void unsetDefaultColor() {}

    private void emitQuad() {
        TextureAtlasSprite sprite = chooseSpriteForQuad();
        if (sprite == null) return;

        if (hasRegionBounds && isBoundarySideQuad()) {
            resetQuadState();
            return;
        }

        String spriteKey = SpriteKeyResolver.resolve(sprite);
        float[] normalizedUVs = normalizeUVs(uvs, sprite);
        float[] normal = computeFaceNormal(positions);
        
        // 计算 Colormap UV (Biome Tint)
        float[] lut = ColorMapManager.remapColorUV(ctx, quadArgb); 
        float u0 = lut[0], v0 = lut[1], u1 = lut[2], v1 = lut[3];
        float du = u1 - u0, dv = v1 - v0;
        
        float[] uv1 = new float[8];
        for (int i = 0; i < 4; i++) {
            uv1[i * 2] = u0 + normalizedUVs[i * 2] * du;
            uv1[i * 2 + 1] = v0 + normalizedUVs[i * 2 + 1] * dv;
        }
        
        float[] linearColors = whiteColor();

        // 发送到 Sink (fluids typically don't have overlays)
        sink.addQuad(materialGroupKey, spriteKey, "voxelbridge:transparent", positions.clone(), normalizedUVs, uv1, null, null, normal, linearColors, true);

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

    private float[] normalizeUVs(float[] input, TextureAtlasSprite s) {
        float[] out = new float[8];
        float u0 = s.getU0(), u1 = s.getU1();
        float v0 = s.getV0(), v1 = s.getV1();
        float du = u1 - u0; if (du == 0) du = 1f;
        float dv = v1 - v0; if (dv == 0) dv = 1f;
        
        for(int i=0; i<4; i++) {
            float u = input[i*2];
            float v = input[i*2+1];
            float su = (u - u0) / du;
            float sv = (v - v0) / dv;
            out[i*2] = clamp01(su);
            out[i*2+1] = clamp01(sv);
        }
        return out;
    }

    private TextureAtlasSprite chooseSpriteForQuad() {
        if (sprites.length == 0) return null;
        if (sprites.length == 1) return sprites[0];
        
        // 简单启发式：检查 UV 中心点更接近哪个 sprite
        float centerU = 0, centerV = 0;
        for(int i=0; i<4; i++) { centerU += uvs[i*2]; centerV += uvs[i*2+1]; }
        centerU /= 4; centerV /= 4;
        
        if (isPointInSprite(centerU, centerV, sprites[0])) return sprites[0];
        if (isPointInSprite(centerU, centerV, sprites[1])) return sprites[1];
        
        // 距离回退
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
        for(int i=0; i<Math.min(rawCount, 4); i++) {
            float vx = rawPositions[i*3];
            minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
        }
        
        // 简化的判定逻辑
        boolean inUnit = minX >= -0.001f && maxX <= 1.001f;
        if (inUnit) {
            needsOffset = true; useChunkOffset = false;
        } else if (minX >= -0.1f && maxX <= 16.1f) {
             // 假设是 Chunk 坐标
            needsOffset = true; useChunkOffset = true;
            chunkOffsetX = Math.floorDiv(pos.getX(), 16) * 16;
            chunkOffsetY = Math.floorDiv(pos.getY(), 16) * 16;
            chunkOffsetZ = Math.floorDiv(pos.getZ(), 16) * 16;
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

    private boolean isBoundarySideQuad() {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        for(int i=0; i<4; i++) { minX = Math.min(minX, positions[i*3]); maxX = Math.max(maxX, positions[i*3]); }
        return Math.abs(minX - regionMinX) < 0.001 || Math.abs(maxX - regionMaxX) < 0.001 || 
               Math.abs(minX - regionMaxX) < 0.001 || Math.abs(maxX - regionMinX) < 0.001; // Z轴同理，此处简化
    }

    private float[] computeFaceNormal(float[] p) {
        float ax=p[3]-p[0], ay=p[4]-p[1], az=p[5]-p[2];
        float bx=p[6]-p[0], by=p[7]-p[1], bz=p[8]-p[2];
        float nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
        float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(l==0) return new float[]{0,1,0};
        return new float[]{nx/l, ny/l, nz/l};
    }
    
    private float clamp01(float v) { return v<0?0:(v>1?1:v); }
    private float[] whiteColor() { return new float[]{1,1,1,1, 1,1,1,1, 1,1,1,1, 1,1,1,1}; }
}