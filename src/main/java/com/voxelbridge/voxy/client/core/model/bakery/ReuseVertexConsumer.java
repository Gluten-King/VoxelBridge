package com.voxelbridge.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.voxy.common.util.MemoryBuffer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;

import static com.voxelbridge.voxy.client.core.model.bakery.BudgetBufferRenderer.VERTEX_FORMAT_SIZE;

public final class ReuseVertexConsumer implements VertexConsumer {
    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    public boolean anyDarkendTex;

    public ReuseVertexConsumer() {
        this.reset();
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
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

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        this.anyDarkendTex |= isDarkCutout(quad);
        if (HAS_NEW_QUAD_API && HAS_UVPAIR) {
            try {
                for (int i = 0; i < 4; i++) {
                    Object pos = POSITION_METHOD.invoke(quad, i);
                    float x = ((Number) POS_X_METHOD.invoke(pos)).floatValue();
                    float y = ((Number) POS_Y_METHOD.invoke(pos)).floatValue();
                    float z = ((Number) POS_Z_METHOD.invoke(pos)).floatValue();
                    this.addVertex(x, y, z);

                    Object uvVal = PACKED_UV_METHOD.invoke(quad, i);
                    long packedUv = (uvVal instanceof Integer)
                        ? ((Integer) uvVal).longValue()
                        : (long) uvVal;
                    this.setUv(unpackU(packedUv), unpackV(packedUv));
                    this.meta(metadata);
                }
                return this;
            } catch (Exception e) {
                // Fall back to legacy vertex buffer decoding.
                com.voxelbridge.util.debug.VoxelBridgeLogger.warn(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                    "[ReuseVC] Reflection API failed, using legacy decoding: " + e.getMessage());
            }
        } else {
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Using legacy vertex decoding (HAS_NEW_QUAD_API=" + HAS_NEW_QUAD_API + ", HAS_UVPAIR=" + HAS_UVPAIR + ")");
        }
        int[] vertices = quad.getVertices();
        // Use the actual buffer length to determine stride. Vanilla keeps 4 vertices packed back-to-back.
        if ((vertices.length & 3) != 0) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.warn(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Unexpected vertex array length (not divisible by 4): " + vertices.length);
            return this;
        }
        int stride = vertices.length / 4;
        int uvOffset = DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0) / 4;
        if (uvOffset < 0 || uvOffset + 1 >= stride) {
            // Fall back to a sane default layout (x,y,z,color,u,v,...). This guards against format changes.
            uvOffset = Math.max(stride - 2, 0);
            if (com.voxelbridge.config.ExportRuntimeConfig.isLodBakeDebugEnabled()) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                    "[ReuseVC] UV offset from DefaultVertexFormat was invalid, fallback uvOffset=" + uvOffset + " stride=" + stride);
            }
        }

        if (com.voxelbridge.config.ExportRuntimeConfig.isLodBakeDebugEnabled() && this.count == 0) {
            // Log first quad's vertex data for debugging
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Legacy decode - stride=" + stride + ", uvOffset=" + uvOffset + ", vertices.length=" + vertices.length);
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] DefaultVertexFormat.BLOCK vertex size: " + DefaultVertexFormat.BLOCK.getVertexSize());
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Raw vertex buffer (all " + vertices.length + " ints): " + java.util.Arrays.toString(vertices));
        }

        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float x = Float.intBitsToFloat(vertices[base]);
            float y = Float.intBitsToFloat(vertices[base + 1]);
            float z = Float.intBitsToFloat(vertices[base + 2]);
            this.addVertex(x, y, z);
            float u = Float.intBitsToFloat(vertices[base + uvOffset]);
            float v = Float.intBitsToFloat(vertices[base + uvOffset + 1]);
            this.setUv(u, v);

            if (com.voxelbridge.config.ExportRuntimeConfig.isLodBakeDebugEnabled() && this.count <= 4) {
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                    String.format("[ReuseVC] Vertex %d: raw[%d,%d,%d]=[%d,%d,%d] -> pos=(%.3f,%.3f,%.3f), raw[%d,%d]=[%d,%d] -> uv=(%.3f,%.3f)",
                        i, base, base+1, base+2, vertices[base], vertices[base+1], vertices[base+2], x, y, z,
                        base+uvOffset, base+uvOffset+1, vertices[base+uvOffset], vertices[base+uvOffset+1], u, v));
            }

            this.meta(metadata);
        }
        return this;
    }

    private static boolean isDarkCutout(BakedQuad quad) {
        try {
            var spriteMethod = quad.getClass().getMethod("getSprite");
            Object sprite = spriteMethod.invoke(quad);
            if (sprite == null) return false;

            var contentsMethod = sprite.getClass().getMethod("contents");
            Object contents = contentsMethod.invoke(sprite);
            if (contents == null) return false;

            Object strategy = null;
            try {
                strategy = contents.getClass().getMethod("mipmapStrategy").invoke(contents);
            } catch (NoSuchMethodException ignored) {
                try {
                    strategy = contents.getClass().getField("mipmapStrategy").get(contents);
                } catch (Exception ignored2) {}
            }
            if (strategy == null) return false;

            Object darkCutout = null;
            Class<?> strategyClass = strategy.getClass();
            try {
                darkCutout = Enum.valueOf((Class<Enum>) strategyClass.asSubclass(Enum.class), "DARK_CUTOUT");
            } catch (Exception ignored) {
                try {
                    darkCutout = strategyClass.getField("DARK_CUTOUT").get(null);
                } catch (Exception ignored2) {}
            }
            return darkCutout != null && strategy.equals(darkCutout);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static float unpackU(long packed) {
        if (UVPAIR_UNPACK_U != null) {
            try {
                if (UVPAIR_UV_LONG) {
                    return ((Number) UVPAIR_UNPACK_U.invoke(null, packed)).floatValue();
                }
                return ((Number) UVPAIR_UNPACK_U.invoke(null, (int) packed)).floatValue();
            } catch (Exception ignored) {
            }
        }
        return Float.intBitsToFloat((int) packed);
    }

    private static float unpackV(long packed) {
        if (UVPAIR_UNPACK_V != null) {
            try {
                if (UVPAIR_UV_LONG) {
                    return ((Number) UVPAIR_UNPACK_V.invoke(null, packed)).floatValue();
                }
                return ((Number) UVPAIR_UNPACK_V.invoke(null, (int) (packed >>> 32))).floatValue();
            } catch (Exception ignored) {
            }
        }
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static final Method POSITION_METHOD;
    private static final Method PACKED_UV_METHOD;
    private static final Method POS_X_METHOD;
    private static final Method POS_Y_METHOD;
    private static final Method POS_Z_METHOD;
    private static final boolean HAS_NEW_QUAD_API;
    private static final Method UVPAIR_UNPACK_U;
    private static final Method UVPAIR_UNPACK_V;
    private static final boolean UVPAIR_UV_LONG;
    private static final boolean HAS_UVPAIR;

    static {
        Method position = null;
        Method packedUv = null;
        Method posX = null;
        Method posY = null;
        Method posZ = null;
        boolean hasNew = false;
        Method unpackU = null;
        Method unpackV = null;
        boolean uvLong = false;
        boolean hasUvpair = false;
        try {
            position = BakedQuad.class.getMethod("position", int.class);
            packedUv = BakedQuad.class.getMethod("packedUV", int.class);
            Class<?> posClass = position.getReturnType();
            posX = posClass.getMethod("x");
            posY = posClass.getMethod("y");
            posZ = posClass.getMethod("z");
            hasNew = true;
            com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] New BakedQuad API detected successfully");
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.warn(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Failed to detect new BakedQuad API: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            com.voxelbridge.util.debug.VoxelBridgeLogger.warn(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] BakedQuad methods: " + java.util.Arrays.toString(BakedQuad.class.getMethods()));
        }
        try {
            Class<?> uvPairClass = Class.forName("net.minecraft.client.model.geom.builders.UVPair");
            try {
                unpackU = uvPairClass.getMethod("unpackU", long.class);
                unpackV = uvPairClass.getMethod("unpackV", long.class);
                uvLong = true;
                hasUvpair = true;
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                    "[ReuseVC] UVPair API detected (long variant)");
            } catch (NoSuchMethodException ignored) {
                unpackU = uvPairClass.getMethod("unpackU", int.class);
                unpackV = uvPairClass.getMethod("unpackV", int.class);
                uvLong = false;
                hasUvpair = true;
                com.voxelbridge.util.debug.VoxelBridgeLogger.info(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                    "[ReuseVC] UVPair API detected (int variant)");
            }
        } catch (Exception e) {
            com.voxelbridge.util.debug.VoxelBridgeLogger.warn(com.voxelbridge.util.debug.LogModule.LOD_BAKE,
                "[ReuseVC] Failed to detect UVPair API: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        POSITION_METHOD = position;
        PACKED_UV_METHOD = packedUv;
        POS_X_METHOD = posX;
        POS_Y_METHOD = posY;
        POS_Z_METHOD = posZ;
        HAS_NEW_QUAD_API = hasNew;
        UVPAIR_UNPACK_U = unpackU;
        UVPAIR_UNPACK_V = unpackV;
        UVPAIR_UV_LONG = uvLong;
        HAS_UVPAIR = hasUvpair;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
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
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
