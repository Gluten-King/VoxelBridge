package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Local stand-in for the removed MultiBufferSource API (MC 26.2+).
 * Capture buffers implement this so geometry can still be collected per RenderType.
 */
public interface BufferSource {
    VertexConsumer getBuffer(RenderType renderType);
}
