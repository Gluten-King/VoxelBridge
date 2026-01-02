package com.voxelbridge.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.voxelbridge.voxy.common.util.MemoryBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static com.voxelbridge.voxy.client.core.model.bakery.BudgetBufferRenderer.VERTEX_FORMAT_SIZE;

public final class ReuseVertexConsumer implements VertexConsumer {
    private static final Method PACKED_UV_METHOD = resolveMethod(BakedQuad.class, "packedUV", int.class);
    private static final Method POSITION_METHOD = resolveMethod(BakedQuad.class, "position", int.class);
    private static final Method UNPACK_U_METHOD = resolveStaticMethod("net.minecraft.client.model.geom.builders.UVPair", "unpackU", long.class);
    private static final Method UNPACK_V_METHOD = resolveStaticMethod("net.minecraft.client.model.geom.builders.UVPair", "unpackV", long.class);
    private static final boolean DEBUG_DISABLE_PACKED = true;
    private static final float UV_EPS = 1.0e-4f;
    private static final float ATLAS_SPAN_THRESHOLD = 0.5f;
    private static volatile int atlasWidth = -1;
    private static volatile int atlasHeight = -1;

    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;
    private float minU;
    private float maxU;
    private float minV;
    private float maxV;
    private boolean uvInvalid;
    private int uvCount;
    private final StringBuilder debugLog = new StringBuilder();
    private boolean captureUvPoints;
    private float[] uvPoints = new float[0];
    private int uvPointCount;

    public ReuseVertexConsumer() {
        this.reset();
    }

    public String getDebugLog() {
        return debugLog.toString();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE;
        this.count++;
        this.meta(this.defaultMeta);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        MemoryUtil.memPutInt(this.ptr + 24, this.tintColor);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    private int tintColor = 0xFFFFFFFF;
    private boolean applyTint = false;

    public void setApplyTint(boolean apply) {
        this.applyTint = apply;
    }

    public void setTintColor(int color) {
        this.tintColor = color;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        // Update tintColor based on the RGBA values provided by the renderer (e.g. LiquidRenderer)
        // Fix: Use ABGR packing (Little Endian -> R G B A in memory) to match OpenGL expectations
        this.tintColor = ((alpha & 0xFF) << 24) | ((blue & 0xFF) << 16) | ((green & 0xFF) << 8) | (red & 0xFF);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        // Input color is 0xAARRGGBB (ARGB)
        // We need 0xAABBGGRR (ABGR) for OpenGL to read R G B A from memory (Little Endian)
        int a = color & 0xFF000000;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        this.tintColor = a | (b << 16) | (g << 8) | r;
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        this.uvCount++;
        if (captureUvPoints) {
            ensureUvPointCapacity(this.uvPointCount + 1);
            int base = this.uvPointCount * 2;
            this.uvPoints[base] = u;
            this.uvPoints[base + 1] = v;
            this.uvPointCount++;
        }
        if (!Float.isFinite(u) || !Float.isFinite(v)) {
            this.uvInvalid = true;
        } else {
            if (u < this.minU) this.minU = u;
            if (u > this.maxU) this.maxU = u;
            if (v < this.minV) this.minV = v;
            if (v > this.maxV) this.maxV = v;
        }
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    public VertexConsumer setLineWidth(float f) {
        return this;
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        this.anyDarkendTex |= isDarkCutout(quad.getSprite());

        int meta = metadata;

        if (tryEmitPacked(quad, meta)) {
            return this;
        }

        int[] vertices = quad.getVertices();
        if ((vertices.length & 3) != 0) {
            return this;
        }
        int stride = vertices.length / 4;
        int uvOffset = DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0) / 4;
        if (uvOffset < 0 || uvOffset + 1 >= stride) {
            uvOffset = Math.max(stride - 2, 0);
        }

        float[] xs = new float[4];
        float[] ys = new float[4];
        float[] zs = new float[4];
        float[] us = new float[4];
        float[] vs = new float[4];
        float rawMinU = Float.POSITIVE_INFINITY;
        float rawMaxU = Float.NEGATIVE_INFINITY;
        float rawMinV = Float.POSITIVE_INFINITY;
        float rawMaxV = Float.NEGATIVE_INFINITY;
        float posMax = 0.0f;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            xs[i] = Float.intBitsToFloat(vertices[base]);
            ys[i] = Float.intBitsToFloat(vertices[base + 1]);
            zs[i] = Float.intBitsToFloat(vertices[base + 2]);
            us[i] = Float.intBitsToFloat(vertices[base + uvOffset]);
            vs[i] = Float.intBitsToFloat(vertices[base + uvOffset + 1]);
            posMax = Math.max(posMax, Math.max(Math.abs(xs[i]), Math.max(Math.abs(ys[i]), Math.abs(zs[i]))));
            if (us[i] < rawMinU) rawMinU = us[i];
            if (us[i] > rawMaxU) rawMaxU = us[i];
            if (vs[i] < rawMinV) rawMinV = vs[i];
            if (vs[i] > rawMaxV) rawMaxV = vs[i];
        }
        if (posMax > 2.0f) {
            float scale = 1.0f / 16.0f;
            for (int i = 0; i < 4; i++) {
                xs[i] *= scale;
                ys[i] *= scale;
                zs[i] *= scale;
            }
        }
        mapUvsIfNeeded(quad.getSprite(), us, vs, rawMinU, rawMaxU, rawMinV, rawMaxV, "verts");
        for (int i = 0; i < 4; i++) {
            this.addVertex(xs[i], ys[i], zs[i]);
            this.setUv(us[i], vs[i]);
            this.meta(meta);
        }
        return this;
    }

    private boolean tryEmitPacked(BakedQuad quad, int meta) {
        if (DEBUG_DISABLE_PACKED) {
            return false;
        }
        if (PACKED_UV_METHOD == null || POSITION_METHOD == null || UNPACK_U_METHOD == null || UNPACK_V_METHOD == null) {
            return false;
        }
        try {
            float[] xs = new float[4];
            float[] ys = new float[4];
            float[] zs = new float[4];
            float[] us = new float[4];
            float[] vs = new float[4];
            float rawMinU = Float.POSITIVE_INFINITY;
            float rawMaxU = Float.NEGATIVE_INFINITY;
            float rawMinV = Float.POSITIVE_INFINITY;
            float rawMaxV = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                Object posObj = POSITION_METHOD.invoke(quad, i);
                float x = readVecComponent(posObj, "x");
                float y = readVecComponent(posObj, "y");
                float z = readVecComponent(posObj, "z");
                long packed = ((Number) PACKED_UV_METHOD.invoke(quad, i)).longValue();
                float u = ((Number) UNPACK_U_METHOD.invoke(null, packed)).floatValue();
                float v = ((Number) UNPACK_V_METHOD.invoke(null, packed)).floatValue();
                xs[i] = x;
                ys[i] = y;
                zs[i] = z;
                us[i] = u;
                vs[i] = v;
                if (u < rawMinU) rawMinU = u;
                if (u > rawMaxU) rawMaxU = u;
                if (v < rawMinV) rawMinV = v;
                if (v > rawMaxV) rawMaxV = v;
            }
            mapUvsIfNeeded(quad.getSprite(), us, vs, rawMinU, rawMaxU, rawMinV, rawMaxV, "packed");
            for (int i = 0; i < 4; i++) {
                this.addVertex(xs[i], ys[i], zs[i]);
                this.setUv(us[i], vs[i]);
                this.meta(meta);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void mapUvsIfNeeded(TextureAtlasSprite sprite,
                                float[] us,
                                float[] vs,
                                float rawMinU,
                                float rawMaxU,
                                float rawMinV,
                                float rawMaxV,
                                String source) {
        if (sprite == null || us == null || vs == null) {
            return;
        }
        if (!Float.isFinite(rawMinU) || !Float.isFinite(rawMaxU)
                || !Float.isFinite(rawMinV) || !Float.isFinite(rawMaxV)) {
            return;
        }

        AtlasBounds bounds = resolveAtlasBounds(sprite);
        if (bounds == null) {
            return;
        }

        float u0 = bounds.u0();
        float u1 = bounds.u1();
        float v0 = bounds.v0();
        float v1 = bounds.v1();

        boolean alreadyAtlas = withinBounds(rawMinU, rawMaxU, u0, u1)
                && withinBounds(rawMinV, rawMaxV, v0, v1);
        if (alreadyAtlas) {
            return;
        }

        boolean normalized01 = withinZeroOne(rawMinU, rawMaxU) && withinZeroOne(rawMinV, rawMaxV);
        if (normalized01) {
            float spanU = u1 - u0;
            float spanV = v1 - v0;
            for (int i = 0; i < 4; i++) {
                us[i] = u0 + us[i] * spanU;
                vs[i] = v0 + vs[i] * spanV;
            }
            return;
        }

        for (int i = 0; i < 4; i++) {
            us[i] = sprite.getU(us[i]);
            vs[i] = sprite.getV(vs[i]);
        }
    }

    private static boolean withinBounds(float min, float max, float bound0, float bound1) {
        float lo = Math.min(bound0, bound1) - UV_EPS;
        float hi = Math.max(bound0, bound1) + UV_EPS;
        return min >= lo && max <= hi;
    }

    private static boolean withinZeroOne(float min, float max) {
        return min >= -UV_EPS && max <= 1.0f + UV_EPS;
    }

    private static AtlasBounds resolveAtlasBounds(TextureAtlasSprite sprite) {
        float u0a = sprite.getU(0);
        float u1a = sprite.getU(16);
        float v0a = sprite.getV(0);
        float v1a = sprite.getV(16);
        float u0b = sprite.getU0();
        float u1b = sprite.getU1();
        float v0b = sprite.getV0();
        float v1b = sprite.getV1();

        float spanA = Math.abs(u1a - u0a);
        float spanB = Math.abs(u1b - u0b);
        boolean aLikelyAtlas = spanA > 0.0f && spanA <= ATLAS_SPAN_THRESHOLD;
        boolean bLikelyAtlas = spanB > 0.0f && spanB <= ATLAS_SPAN_THRESHOLD;

        if (aLikelyAtlas && (!bLikelyAtlas || spanA <= spanB)) {
            return new AtlasBounds(u0a, u1a, v0a, v1a, "getU(0/16)");
        }
        if (bLikelyAtlas) {
            return new AtlasBounds(u0b, u1b, v0b, v1b, "getU0/1");
        }

        AtlasBounds reflected = resolveAtlasBoundsFromSprite(sprite);
        if (reflected != null) {
            return reflected;
        }

        float spanPick = spanA > 0.0f ? spanA : spanB;
        boolean useA = spanA > 0.0f && (spanB <= 0.0f || spanA <= spanB);
        String source = useA ? "getU(0/16)" : "getU0/1";
        if (spanPick <= 0.0f) {
            return null;
        }
        return new AtlasBounds(useA ? u0a : u0b, useA ? u1a : u1b, useA ? v0a : v0b, useA ? v1a : v1b, source);
    }

    private static AtlasBounds resolveAtlasBoundsFromSprite(TextureAtlasSprite sprite) {
        Integer x = readIntProperty(sprite, "getX", "x");
        Integer y = readIntProperty(sprite, "getY", "y");
        if (x == null || y == null) {
            Object contents = invokeOptional(sprite, "contents");
            if (contents != null) {
                x = readIntProperty(contents, "getX", "x");
                y = readIntProperty(contents, "getY", "y");
            }
        }
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        ensureAtlasSize();
        if (x == null || y == null || atlasWidth <= 0 || atlasHeight <= 0 || w <= 0 || h <= 0) {
            return null;
        }
        float u0 = (float) x / atlasWidth;
        float u1 = (float) (x + w) / atlasWidth;
        float v0 = (float) y / atlasHeight;
        float v1 = (float) (y + h) / atlasHeight;
        return new AtlasBounds(u0, u1, v0, v1, "xy/atlas");
    }

    private static void ensureAtlasSize() {
        if (atlasWidth > 0 && atlasHeight > 0) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) {
                return;
            }
            Object atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            Integer w = readIntProperty(atlas, "getWidth", "width");
            Integer h = readIntProperty(atlas, "getHeight", "height");
            if (w != null && w > 0 && h != null && h > 0) {
                atlasWidth = w;
                atlasHeight = h;
            }
        } catch (Exception ignored) {
        }
    }

    private static Integer readIntProperty(Object obj, String... names) {
        if (obj == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field field = obj.getClass().getField(name);
                Object val = field.get(obj);
                if (val instanceof Number num) {
                    return num.intValue();
                }
            } catch (Exception ignored) {
            }
            try {
                Method method = obj.getClass().getMethod(name);
                Object val = method.invoke(obj);
                if (val instanceof Number num) {
                    return num.intValue();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Object invokeOptional(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record AtlasBounds(float u0, float u1, float v0, float v1, String source) {}

    private static float readVecComponent(Object obj, String name) {
        if (obj == null) {
            return 0.0f;
        }
        try {
            Field field = obj.getClass().getField(name);
            Class<?> type = field.getType();
            if (type == float.class) {
                return field.getFloat(obj);
            }
            if (type == double.class) {
                return (float) field.getDouble(obj);
            }
            Object val = field.get(obj);
            if (val instanceof Number num) {
                return num.floatValue();
            }
        } catch (Exception ignored) {
        }
        try {
            Method method = obj.getClass().getMethod(name);
            Object val = method.invoke(obj);
            if (val instanceof Number num) {
                return num.floatValue();
            }
        } catch (Exception ignored) {
        }
        return 0.0f;
    }

    private static Method resolveMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Exception ignored) {
            try {
                Method method = type.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private static Method resolveStaticMethod(String className, String name, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(className);
            Method method = cls.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr - this.buffer.address;
        var newBuffer = new MemoryBuffer((((int) (this.buffer.size * 2) + VERTEX_FORMAT_SIZE - 1)
                / VERTEX_FORMAT_SIZE) * VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    private void ensureUvPointCapacity(int neededPoints) {
        int needed = neededPoints * 2;
        if (this.uvPoints.length >= needed) {
            return;
        }
        int next = Math.max(needed, this.uvPoints.length == 0 ? 128 : this.uvPoints.length * 2);
        float[] resized = new float[next];
        if (this.uvPoints.length > 0) {
            System.arraycopy(this.uvPoints, 0, resized, 0, this.uvPointCount * 2);
        }
        this.uvPoints = resized;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.defaultMeta = 0;
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;
        this.minU = Float.POSITIVE_INFINITY;
        this.maxU = Float.NEGATIVE_INFINITY;
        this.minV = Float.POSITIVE_INFINITY;
        this.maxV = Float.NEGATIVE_INFINITY;
        this.uvInvalid = false;
        this.uvCount = 0;
        this.debugLog.setLength(0);
        this.uvPointCount = 0;
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count % 4 != 0) throw new IllegalStateException();
        return this.count / 4;
    }

    public long getAddress() {
        return this.buffer.address;
    }

    public float getMinU() {
        return this.minU;
    }

    public float getMaxU() {
        return this.maxU;
    }

    public float getMinV() {
        return this.minV;
    }

    public float getMaxV() {
        return this.maxV;
    }

    public boolean hasInvalidUv() {
        return this.uvInvalid;
    }

    public int getUvCount() {
        return this.uvCount;
    }

    public void setCaptureUvPoints(boolean capture) {
        this.captureUvPoints = capture;
    }

    public int getUvPointCount() {
        return this.uvPointCount;
    }

    public float[] copyUvPoints() {
        if (this.uvPointCount == 0) {
            return new float[0];
        }
        float[] copy = new float[this.uvPointCount * 2];
        System.arraycopy(this.uvPoints, 0, copy, 0, copy.length);
        return copy;
    }

    private static boolean isOverlaySprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return false;
        }
        return sprite.contents().name().toString().contains("overlay");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
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
                Object enumVal = Enum.valueOf((Class<? extends Enum>) strategy.getClass(), "DARK_CUTOUT");
                return strategy.equals(enumVal);
            } catch (Exception ignored) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }
}
