package com.voxelbridge.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU 版的 LOD bake，完全绕过 GL，直接用 BakedQuad 数据栅格化到 16x16 贴图。
 */
public final class CpuModelTextureBakery {
    private static final int SIZE = 16;
    private static final int MAX_DEPTH = (1 << 24) - 1;
    private static final Matrix4f[] VIEWS = new Matrix4f[6];
    private static final Matrix4f[] CLIP_MATS = new Matrix4f[6];

    static {
        addView(0, -90, 0, 0, 0);//DOWN
        addView(1, 90, 0, 0, 0b100);//UP
        addView(2, 0, 180, 0, 0b001);//NORTH
        addView(3, 0, 0, 0, 0);//SOUTH
        addView(4, 0, 90, 270, 0b100);//WEST
        addView(5, 0, 270, 270, 0);//EAST
        Matrix4f proj = new Matrix4f()
            .set(2, 0, 0, 0,
                 0, 2, 0, 0,
                 0, 0, -1f, 0,
                 -1, -1, 0, 1);
        for (int i = 0; i < 6; i++) {
            CLIP_MATS[i] = new Matrix4f(proj).mul(VIEWS[i]);
        }
    }

    public enum BakeMode {
        FULL,
        BASE_ONLY,
        OVERLAY_ONLY
    }

    public record BakeResult(ColourDepthTextureData[] textures, boolean isShaded, boolean darkenedTinting) {}

    public BakeResult bake(BlockState state) {
        return bake(state, BakeMode.FULL);
    }

    public BakeResult bake(BlockState state, BakeMode mode) {
        RenderType layer;
        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else if (state.getBlock() instanceof LeavesBlock) {
            layer = RenderType.solid();
        } else {
            layer = ItemBlockRenderTypes.getChunkRenderType(state);
        }

        List<CpuQuad> quads = new ArrayList<>();
        boolean anyShaded = false;
        boolean anyDark = false;

        boolean includeBase = mode != BakeMode.OVERLAY_ONLY;
        boolean includeOverlay = mode != BakeMode.BASE_ONLY;

        if (isBlock) {
            var model = Minecraft.getInstance()
                    .getModelManager()
                    .getBlockModelShaper()
                    .getBlockModel(state);

            int metaBase = getMetaFromLayer(layer);
            RandomSource rand = RandomSource.create(42L);
            long seed = 42L;
            for (Direction dir : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                rand.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, dir, rand, ModelData.EMPTY, layer)) {
                    boolean isOverlay = isOverlaySprite(quad.getSprite());
                    if (isOverlay ? !includeOverlay : !includeBase) {
                        continue;
                    }
                    int meta = metaBase;
                    CpuQuad cq = decodeQuad(quad, meta);
                    if (cq == null) continue;
                    quads.add(cq);
                    anyShaded |= cq.shaded;
                    anyDark |= cq.darkCutout;
                }
            }
            // 额外抓取未按图层过滤的 overlay quad（如 grass_block_side_overlay 可能处于不同 RenderType）
            rand.setSeed(seed);
            for (Direction dir : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                rand.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, dir, rand, ModelData.EMPTY, null)) {
                    if (!isOverlaySprite(quad.getSprite())) {
                        continue;
                    }
                    if (!includeOverlay) {
                        continue;
                    }
                    int meta = metaBase;
                    CpuQuad cq = decodeQuad(quad, meta);
                    if (cq == null) continue;
                    quads.add(cq);
                    anyShaded |= cq.shaded;
                    anyDark |= cq.darkCutout;
                }
            }
        } else {
            // 简易流体支持：用缓存的 still/flow 贴图生成满方块 6 面
            FluidSprites sprites = FluidSprites.of(state.getFluidState());
            int meta = getMetaFromLayer(layer);
            for (int face = 0; face < 6; face++) {
                TextureAtlasSprite sprite = (face == Direction.UP.get3DDataValue() || face == Direction.DOWN.get3DDataValue())
                        ? sprites.still : sprites.flow;
                quads.add(makeUnitFace(face, sprite, meta));
            }
        }

        // overlay 贴图（如 grass_block_side_overlay）放到最后绘制，避免被同深度基底覆盖
        if (includeOverlay && includeBase) {
            quads.sort((a, b) -> Boolean.compare(isOverlaySprite(a.sprite), isOverlaySprite(b.sprite)));
        }

        ColourDepthTextureData[] out = new ColourDepthTextureData[6];
        for (int face = 0; face < 6; face++) {
            out[face] = rasterizeFace(quads, face);
        }
        return new BakeResult(out, anyShaded, anyDark);
    }

    private static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = isCutout(layer) || isTranslucent(layer) || isTripwire(layer);
        boolean isMipped = isSolid(layer) || isTranslucent(layer) || isTripwire(layer);
        int meta = hasDiscard ? 1 : 0;
        meta |= isMipped ? 2 : 0;
        return meta;
    }

    private static boolean isSolid(RenderType layer) { return layer == RenderType.solid(); }
    private static boolean isCutout(RenderType layer) { return layer == RenderType.cutout() || layer == RenderType.cutoutMipped(); }
    private static boolean isTranslucent(RenderType layer) { return layer == RenderType.translucent(); }
    private static boolean isTripwire(RenderType layer) { return layer == RenderType.tripwire(); }

    private record CpuQuad(float[][] pos, float[][] uv, int meta, TextureAtlasSprite sprite, boolean shaded, boolean darkCutout) {}

    private static CpuQuad decodeQuad(BakedQuad quad, int metadata) {
        float[][] pos = new float[4][3];
        float[][] uv = new float[4][2];

        int[] vertices = quad.getVertices();
        if ((vertices.length & 3) != 0) return null;
        int stride = vertices.length / 4;
        int uvOffset = DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0) / 4;
        if (uvOffset < 0 || uvOffset + 1 >= stride) uvOffset = Math.max(stride - 2, 0);

        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            pos[i][0] = Float.intBitsToFloat(vertices[base]);
            pos[i][1] = Float.intBitsToFloat(vertices[base + 1]);
            pos[i][2] = Float.intBitsToFloat(vertices[base + 2]);
            uv[i][0] = Float.intBitsToFloat(vertices[base + uvOffset]);
            uv[i][1] = Float.intBitsToFloat(vertices[base + uvOffset + 1]);
        }
        TextureAtlasSprite sprite = quad.getSprite();
        boolean dark = isDarkCutout(sprite);
        return new CpuQuad(pos, uv, metadata, sprite, quad.isShade(), dark);
    }

    private static boolean isDarkCutout(TextureAtlasSprite sprite) {
        try {
            var contents = sprite.contents();
            Object strategy = null;
            try {
                strategy = contents.getClass().getMethod("mipmapStrategy").invoke(contents);
            } catch (Exception ignored) {
                try {
                    strategy = contents.getClass().getField("mipmapStrategy").get(contents);
                } catch (Exception ignored2) {}
            }
            if (strategy == null) return false;
            try {
                Object enumVal = java.lang.Enum.valueOf((Class<? extends Enum>) strategy.getClass(), "DARK_CUTOUT");
                return strategy.equals(enumVal);
            } catch (Exception ignored) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOverlaySprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return false;
        }
        String name = sprite.contents().name().toString();
        return name.contains("overlay");
    }

    private ColourDepthTextureData rasterizeFace(List<CpuQuad> quads, int faceIdx) {
        int[] colour = new int[SIZE * SIZE];
        int[] depth = new int[SIZE * SIZE];
        // 初始化为最大深度、零模板
        int clear = (MAX_DEPTH << 8);
        for (int i = 0; i < depth.length; i++) depth[i] = clear;

        Matrix4f mat = CLIP_MATS[faceIdx];
        for (CpuQuad q : quads) {
            if (q.sprite == null) continue;
            drawQuad(q, mat, colour, depth);
        }
        // 修正方向：部分面在 CPU 光栅后需要旋转/翻转以匹配 GL 版本
        fixOrientation(faceIdx, colour, depth);
        return new ColourDepthTextureData(colour, depth, SIZE, SIZE);
    }

    private void drawQuad(CpuQuad quad, Matrix4f mat, int[] colour, int[] depthBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Vector4f[] v = new Vector4f[4];
            float[] sx = new float[4];
            float[] sy = new float[4];
            float[] sz = new float[4];
            for (int i = 0; i < 4; i++) {
                v[i] = new Vector4f(quad.pos[i][0], quad.pos[i][1], quad.pos[i][2], 1.0f).mul(mat);
                float invW = 1.0f / v[i].w;
                float ndcX = v[i].x * invW;
                float ndcY = v[i].y * invW;
                float ndcZ = v[i].z * invW;
                sx[i] = (ndcX * 0.5f + 0.5f) * SIZE;
                sy[i] = (ndcY * 0.5f + 0.5f) * SIZE;
                sz[i] = clamp01(ndcZ * 0.5f + 0.5f);
            }
            // 两个三角
            drawTri(0,1,2, quad, sx,sy,sz, colour, depthBuf);
            drawTri(0,2,3, quad, sx,sy,sz, colour, depthBuf);
        }
    }

    /**
     * 调整各面的像素方向以匹配原 GPU 烘焙。
     * faceIdx: 0=DOWN,1=UP,2=NORTH,3=SOUTH,4=WEST,5=EAST
     */
    private void fixOrientation(int faceIdx, int[] colour, int[] depth) {
        // 顶/前/后翻转
        if (faceIdx == 1 || faceIdx == 2 || faceIdx == 3) {
            flipY(colour);
            flipY(depth);
        }
        // WEST/EAST 旋转校正
        if (faceIdx == 4 || faceIdx == 5) {
            rotateCCW(colour);
            rotateCCW(depth);
        }
    }

    private void flipY(int[] buf) {
        for (int y = 0; y < SIZE / 2; y++) {
            int y2 = SIZE - 1 - y;
            for (int x = 0; x < SIZE; x++) {
                int idx1 = y * SIZE + x;
                int idx2 = y2 * SIZE + x;
                int tmp = buf[idx1];
                buf[idx1] = buf[idx2];
                buf[idx2] = tmp;
            }
        }
    }

    private void rotateCW(int[] buf) {
        int[] tmp = buf.clone();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int nx = SIZE - 1 - y;
                int ny = x;
                buf[ny * SIZE + nx] = tmp[y * SIZE + x];
            }
        }
    }

    private void rotateCCW(int[] buf) {
        int[] tmp = buf.clone();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int nx = y;
                int ny = SIZE - 1 - x;
                buf[ny * SIZE + nx] = tmp[y * SIZE + x];
            }
        }
    }

    private void drawTri(int ia, int ib, int ic, CpuQuad q, float[] sx, float[] sy, float[] sz, int[] colour, int[] depthBuf) {
        float ax = sx[ia], ay = sy[ia];
        float bx = sx[ib], by = sy[ib];
        float cx = sx[ic], cy = sy[ic];
        float area = edge(ax, ay, bx, by, cx, cy);
        if (area == 0) return;
        float invArea = 1.0f / area;

        int minX = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
        int maxX = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
        int minY = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
        int maxY = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));

        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        maxX = Math.min(maxX, SIZE);
        maxY = Math.min(maxY, SIZE);

        boolean discard = (q.meta & 1) != 0;
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < maxX; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float w0 = edge(bx, by, cx, cy, px, py);
                float w1 = edge(cx, cy, ax, ay, px, py);
                float w2 = edge(ax, ay, bx, by, px, py);
                if ((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0)) {
                    w0 *= invArea; w1 *= invArea; w2 *= invArea;
                    float depth = w0 * sz[ia] + w1 * sz[ib] + w2 * sz[ic];
                    int depth24 = clampDepth(depth);
                    int idx = y * SIZE + x; // 注意 y=0 在底部
                    int prev = depthBuf[idx];
                    int prevDepth = prev >>> 8;
                    // 使用 > 而不是 >=，确保与原版 LEQUAL 深度测试一致，这样同深度的覆盖层（如草方块侧面 overlay）可以覆盖前一个片元
                    if (depth24 > prevDepth) {
                        continue;
                    }
                    float u = w0 * q.uv[ia][0] + w1 * q.uv[ib][0] + w2 * q.uv[ic][0];
                    float v = w0 * q.uv[ia][1] + w1 * q.uv[ib][1] + w2 * q.uv[ic][1];
                    // 转为贴图内部坐标（BakedQuad UV 是 atlas 坐标）
                    float su = toSpriteU(q.sprite, u);
                    float sv = toSpriteV(q.sprite, v);
                    int abgr = sample(q.sprite, su, sv);
                    float alpha = ((abgr >>> 24) & 0xFF) / 255f;
                    if (discard && alpha < 0.001f) {
                        continue;
                    }
                    int stencil = (prev & 0xFF) + 1;
                    if (stencil > 0xFF) stencil = 0xFF;
                    depthBuf[idx] = (depth24 << 8) | stencil;
                    colour[idx] = abgr;
                }
            }
        }
    }

    private static float toSpriteU(TextureAtlasSprite sprite, float u) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float denom = (u1 - u0);
        if (denom == 0) return 0f;
        return (u - u0) / denom;
    }

    private static float toSpriteV(TextureAtlasSprite sprite, float v) {
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float denom = (v1 - v0);
        if (denom == 0) return 0f;
        return (v - v0) / denom;
    }

    private static int sample(TextureAtlasSprite sprite, float u, float v) {
        // clamp uv
        u = Math.max(0f, Math.min(1f, u));
        v = Math.max(0f, Math.min(1f, v));
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int px = Math.min(w - 1, Math.max(0, (int) (u * w)));
        int py = Math.min(h - 1, Math.max(0, (int) ((1.0f - v) * h)));
        int abgr = sprite.contents().getOriginalImage().getPixelRGBA(px, py);
        return abgr;
    }

    private static float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private static int clampDepth(float depth) {
        float d = clamp01(depth);
        return (int) (d * MAX_DEPTH + 0.5f);
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                (float) Math.cos(hangle));
    }

    /**
     * 简易流体贴图分辨，尽量贴近 vanilla 使用的 still/flow。
     */
    private record FluidSprites(TextureAtlasSprite still, TextureAtlasSprite flow) {
        static FluidSprites of(FluidState fs) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            TextureAtlasSprite missing = atlas.getSprite(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
            TextureAtlasSprite still = missing;
            TextureAtlasSprite flow = missing;
            try {
                var sprites = net.neoforged.neoforge.client.textures.FluidSpriteCache.getFluidSprites(new DummyGetter(), BlockPos.ZERO, fs);
                if (sprites != null && sprites.length >= 2) {
                    if (sprites[0] != null) still = sprites[0];
                    if (sprites[1] != null) flow = sprites[1];
                }
            } catch (Throwable ignored) {
            }
            return new FluidSprites(still, flow);
        }
    }

    /**
     * 渲染流体时需要的最小 BlockAndTintGetter 实现。
     */
    private static final class DummyGetter implements BlockAndTintGetter {
        @Override public float getShade(Direction direction, boolean shaded) { return 0; }
        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return null; }
        @Override public int getBrightness(LightLayer type, BlockPos pos) { return 0; }
        @Override public int getBlockTint(BlockPos pos, ColorResolver colorResolver) { return 0; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return Blocks.AIR.defaultBlockState().getFluidState(); }
        @Override public int getHeight() { return 0; }
        @Override public int getMinBuildHeight() { return 0; }
    }

    private static CpuQuad makeUnitFace(int face, TextureAtlasSprite sprite, int meta) {
        // 立方体 6 面，按照 Direction.get3DDataValue 顺序
        float[][] p = switch (face) {
            case 0 -> new float[][]{{0,0,0},{1,0,0},{1,0,1},{0,0,1}}; //DOWN
            case 1 -> new float[][]{{0,1,0},{0,1,1},{1,1,1},{1,1,0}}; //UP
            case 2 -> new float[][]{{1,0,0},{1,1,0},{0,1,0},{0,0,0}}; //NORTH
            case 3 -> new float[][]{{0,0,1},{0,1,1},{1,1,1},{1,0,1}}; //SOUTH
            case 4 -> new float[][]{{0,0,0},{0,1,0},{0,1,1},{0,0,1}}; //WEST
            default -> new float[][]{{1,0,1},{1,1,1},{1,1,0},{1,0,0}}; //EAST
        };
        float[][] uv = new float[][]{
            {0f,0f},{0f,1f},{1f,1f},{1f,0f}
        };
        return new CpuQuad(p, uv, meta, sprite, false, false);
    }
}
