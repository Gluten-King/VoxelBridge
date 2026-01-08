package com.voxelbridge.platform.render.capture;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared render capture buffer for entity and block entity renders.
 * Collects vertices per RenderLayer and emits primitives to a callback.
 */
public final class RenderCapture implements VertexConsumerProvider {
    public interface QuadSink {
        void onQuad(RenderLayer renderLayer, List<Vertex> verts);
    }

    public interface DebugSink {
        void onSetNormal(RenderLayer renderLayer, int queuedVertices);
    }

    public static final class Vertex {
        public float x, y, z;
        public float u, v;
        public int color = 0xFFFFFFFF;

        public Vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Map<RenderLayer, VertexCollector> collectors = new HashMap<>();
    private final QuadSink quadSink;
    private final DebugSink debugSink;

    public RenderCapture(QuadSink quadSink, DebugSink debugSink) {
        this.quadSink = quadSink;
        this.debugSink = debugSink;
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer renderLayer) {
        return collectors.computeIfAbsent(renderLayer, VertexCollector::new);
    }

    public void flush() {
        for (VertexCollector collector : collectors.values()) {
            collector.flush();
        }
    }

    private final class VertexCollector implements VertexConsumer {
        private final RenderLayer renderLayer;
        private final ArrayDeque<Vertex> vertices = new ArrayDeque<>(8);
        private final VertexFormat.DrawMode drawMode;
        private final int vertsPerPrimitive;

        private VertexCollector(RenderLayer renderLayer) {
            this.renderLayer = renderLayer;
            this.drawMode = renderLayer.getDrawMode();
            this.vertsPerPrimitive = switch (drawMode) {
                case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> 3;
                case LINES, LINE_STRIP -> 2;
                case QUADS -> 4;
                default -> 4;
            };
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            vertices.addLast(new Vertex(x, y, z));
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
        public VertexConsumer texture(float u, float v) {
            Vertex last = vertices.peekLast();
            if (last != null) {
                last.u = u;
                last.v = v;
            }
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) { return this; }

        @Override
        public VertexConsumer light(int u, int v) { return this; }

        @Override
        public VertexConsumer normal(float nx, float ny, float nz) {
            if (debugSink != null) {
                debugSink.onSetNormal(renderLayer, vertices.size());
            }
            emitReadyPrimitives(false);
            return this;
        }

        void flush() {
            emitReadyPrimitives(true);
        }

        private void emitReadyPrimitives(boolean flushRemainder) {
            while (vertices.size() >= vertsPerPrimitive) {
                quadSink.onQuad(renderLayer, extractPrimitive(vertsPerPrimitive));
            }
            if (flushRemainder && vertices.size() >= 3) {
                quadSink.onQuad(renderLayer, extractPrimitive(vertices.size()));
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
