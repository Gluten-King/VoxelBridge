package com.voxelbridge.platform.render.capture;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;

/**
 * Shared base for render capture buffers (entities and block entities).
 */
public abstract class CaptureBufferBase implements VertexConsumerProvider, RenderCapture.QuadSink {
    protected final ExportContext ctx;
    protected final IrSink sceneSink;
    private final RenderCapture capture;
    private boolean hadGeometry;

    protected CaptureBufferBase(ExportContext ctx, IrSink sceneSink, RenderCapture.DebugSink debugSink) {
        this.ctx = ctx;
        this.sceneSink = sceneSink;
        this.capture = new RenderCapture(this, debugSink);
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer renderLayer) {
        return capture.getBuffer(renderLayer);
    }

    protected void flushCapture() {
        capture.flush();
    }

    protected void recordGeometry() {
        this.hadGeometry = true;
    }

    public boolean hadGeometry() {
        return hadGeometry;
    }
}
