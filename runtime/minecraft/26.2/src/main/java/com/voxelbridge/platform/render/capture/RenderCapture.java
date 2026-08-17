package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.*;

/**
 * Shared render capture buffer for entity and block entity renders.
 * Collects vertices per RenderType and emits primitives to a callback.
 * <p>
 * MC 26.2: MultiBufferSource removed; RenderType.mode() became primitiveTopology().
 */
public final class RenderCapture implements BufferSource {
    public interface QuadSink {
        void onQuad(RenderType renderType, Submission submission, List<Vertex> verts);
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

    private record CollectorKey(RenderType renderType, Submission submission) {}

    private final Map<CollectorKey, VertexCollector> collectors = new HashMap<>();
    private final QuadSink quadSink;
    private final DebugSink debugSink;

    public RenderCapture(QuadSink quadSink, DebugSink debugSink) {
        this.quadSink = quadSink;
        this.debugSink = debugSink;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return getBuffer(renderType, null, UvSpace.UNKNOWN);
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                                    UvSpace uvSpace) {
        Submission submission = new Submission(sprite, uvSpace != null ? uvSpace : UvSpace.UNKNOWN);
        CollectorKey key = new CollectorKey(renderType, submission);
        return collectors.computeIfAbsent(key, ignored -> new VertexCollector(renderType, submission));
    }

    public void flush() {
        for (VertexCollector collector : collectors.values()) {
            collector.flush();
        }
    }

    public void endBatch(RenderType renderType) {
        for (Map.Entry<CollectorKey, VertexCollector> entry : collectors.entrySet()) {
            if (Objects.equals(entry.getKey().renderType(), renderType)) {
                entry.getValue().flush();
            }
        }
    }

    @Override
    public void endBatch(RenderType renderType, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                         UvSpace uvSpace) {
        VertexCollector collector = collectors.get(new CollectorKey(
            renderType, new Submission(sprite, uvSpace != null ? uvSpace : UvSpace.UNKNOWN)));
        if (collector != null) {
            collector.flush();
        }
    }

    private final class VertexCollector implements VertexConsumer {
        private final RenderType renderType;
        private final Submission submission;
        private final ArrayList<Vertex> vertices = new ArrayList<>(8);
        private final PrimitiveTopology mode;
        private int stripTriangleIndex;

        private VertexCollector(RenderType renderType, Submission submission) {
            this.renderType = renderType;
            this.submission = submission;
            this.mode = renderType.primitiveTopology();
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices.add(new Vertex(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            Vertex last = lastVertex();
            if (last != null) {
                last.color = (a << 24) | (r << 16) | (g << 8) | b;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            Vertex last = lastVertex();
            if (last != null) {
                last.color = color;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            Vertex last = lastVertex();
            if (last != null) {
                last.hasUv = Float.isFinite(u) && Float.isFinite(v);
                last.u = last.hasUv ? u : 0f;
                last.v = last.hasUv ? v : 0f;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) { return this; }

        @Override
        public VertexConsumer setUv2(int u, int v) { return this; }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            if (debugSink != null) {
                debugSink.onSetNormal(renderType, vertices.size());
            }
            emitReadyPrimitives();
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        void flush() {
            emitReadyPrimitives();
            vertices.clear();
            stripTriangleIndex = 0;
        }

        private void emitReadyPrimitives() {
            switch (mode) {
                case TRIANGLES -> emitGroups(3);
                case QUADS -> emitGroups(4);
                case TRIANGLE_STRIP -> emitTriangleStrip();
                case TRIANGLE_FAN -> emitTriangleFan();
                default -> emitGroups(4);
            }
        }

        private void emitGroups(int count) {
            while (vertices.size() >= count) {
                quadSink.onQuad(renderType, submission, new ArrayList<>(vertices.subList(0, count)));
                vertices.subList(0, count).clear();
            }
        }

        private void emitTriangleStrip() {
            while (vertices.size() >= 3) {
                Vertex a = vertices.get(0);
                Vertex b = vertices.get(1);
                Vertex c = vertices.get(2);
                quadSink.onQuad(renderType, submission, (stripTriangleIndex++ & 1) == 0
                    ? List.of(a, b, c)
                    : List.of(b, a, c));
                vertices.remove(0);
            }
        }

        private void emitTriangleFan() {
            while (vertices.size() >= 3) {
                Vertex anchor = vertices.get(0);
                Vertex previous = vertices.get(1);
                Vertex current = vertices.get(2);
                quadSink.onQuad(renderType, submission, List.of(anchor, previous, current));
                vertices.remove(1);
            }
        }

        private Vertex lastVertex() {
            return vertices.isEmpty() ? null : vertices.get(vertices.size() - 1);
        }
    }
}
