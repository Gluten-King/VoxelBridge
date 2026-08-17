package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Local stand-in for the removed MultiBufferSource API (MC 26.2+).
 * Capture buffers implement this so geometry can still be collected per RenderType.
 */
public interface BufferSource {
    enum UvSpace {
        UNKNOWN,
        SPRITE_LOCAL,
        ATLAS
    }

    record Submission(TextureAtlasSprite sprite, UvSpace uvSpace) {
        public static final Submission UNKNOWN = new Submission(null, UvSpace.UNKNOWN);
    }

    VertexConsumer getBuffer(RenderType renderType);

    default VertexConsumer getBuffer(RenderType renderType, TextureAtlasSprite sprite, UvSpace uvSpace) {
        return getBuffer(renderType);
    }

    /** Ends one renderer submission so strip/fan topology cannot bridge nodes. */
    default void endBatch(RenderType renderType) {
    }

    default void endBatch(RenderType renderType, TextureAtlasSprite sprite, UvSpace uvSpace) {
        endBatch(renderType);
    }
}
