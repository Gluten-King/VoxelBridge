package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.*;

/**
 * Shared render capture buffer for entity and block entity renders.
 * Collects vertices per RenderType and emits primitives to a callback.
 */
public final class RenderCapture implements MultiBufferSource {
    public interface QuadSink {
        void onQuad(RenderType renderType, List<Vertex> verts);
    }

    public interface DebugSink {
        void onSetNormal(RenderType renderType, int queuedVertices);
    }

    public static final class Vertex {
        public float x, y, z;
        public float u, v;
        public boolean hasUv;
        public int color = 0xFFFFFFFF;

        public Vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Map<RenderType, VertexCollector> collectors = new HashMap<>();
    private final QuadSink quadSink;
    private final DebugSink debugSink;

    public RenderCapture(QuadSink quadSink, DebugSink debugSink) {
        this.quadSink = quadSink;
        this.debugSink = debugSink;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return collectors.computeIfAbsent(renderType, rt -> new VertexCollector(rt));
    }

    public void flush() {
        for (VertexCollector collector : collectors.values()) {
            collector.flush();
        }
    }

    private final class VertexCollector implements VertexConsumer {
        private final RenderType renderType;
        private final ArrayDeque<Vertex> vertices = new ArrayDeque<>(8);
        private final com.mojang.blaze3d.vertex.VertexFormat.Mode mode;
        private final int vertsPerPrimitive;

        private VertexCollector(RenderType renderType) {
            this.renderType = renderType;
            this.mode = renderType.mode();
            this.vertsPerPrimitive = switch (mode) {
                case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> 3;
                case QUADS -> 4;
                default -> 4;
            };
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            vertices.addLast(new Vertex((float) x, (float) y, (float) z));
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            Vertex last = vertices.peekLast();
            if (last != null) {
                last.color = (a << 24) | (r << 16) | (g << 8) | b;
            }
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            Vertex last = vertices.peekLast();
            if (last != null) {
                last.hasUv = Float.isFinite(u) && Float.isFinite(v);
                last.u = last.hasUv ? u : 0f;
                last.v = last.hasUv ? v : 0f;
            }
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) { return this; }

        @Override
        public VertexConsumer uv2(int u, int v) { return this; }

        @Override
        public VertexConsumer normal(float nx, float ny, float nz) {
            if (debugSink != null) {
                debugSink.onSetNormal(renderType, vertices.size());
            }
            return this;
        }

        @Override
        public void endVertex() {
            emitReadyPrimitives(false);
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        void flush() {
            emitReadyPrimitives(true);
        }

        private void emitReadyPrimitives(boolean flushRemainder) {
            while (vertices.size() >= vertsPerPrimitive) {
                quadSink.onQuad(renderType, extractPrimitive(vertsPerPrimitive));
            }
            if (flushRemainder && vertices.size() >= 3) {
                quadSink.onQuad(renderType, extractPrimitive(vertices.size()));
            }
        }

        private List<Vertex> extractPrimitive(int count) {
            List<Vertex> prim = new ArrayList<>(count);
            for (int i = 0; i < count && !vertices.isEmpty(); i++) {
                prim.add(vertices.removeFirst());
            }
            return prim;
        }
    }
}
