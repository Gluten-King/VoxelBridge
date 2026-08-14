package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Shared base for render capture buffers (entities and block entities).
 */
public abstract class CaptureBufferBase implements MultiBufferSource, RenderCapture.QuadSink {
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
    public VertexConsumer getBuffer(RenderType renderType) {
        return capture.getBuffer(renderType);
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
