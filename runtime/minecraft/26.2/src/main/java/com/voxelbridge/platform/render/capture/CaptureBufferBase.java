package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Shared base for render capture buffers (entities and block entities).
 * MC 26.2: implements local BufferSource instead of removed MultiBufferSource.
 */
public abstract class CaptureBufferBase implements BufferSource, RenderCapture.QuadSink {
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

    @Override
    public VertexConsumer getBuffer(RenderType renderType, TextureAtlasSprite sprite, UvSpace uvSpace) {
        return capture.getBuffer(renderType, sprite, uvSpace);
    }

    @Override
    public void endBatch(RenderType renderType) {
        capture.endBatch(renderType);
    }

    @Override
    public void endBatch(RenderType renderType, TextureAtlasSprite sprite, UvSpace uvSpace) {
        capture.endBatch(renderType, sprite, uvSpace);
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
