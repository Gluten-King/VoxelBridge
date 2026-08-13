package com.voxelbridge.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

final class SelectionRenderTypes {
    private static final RenderType[] LINES = createLines();

    private SelectionRenderTypes() {}

    static RenderType lines(int width) {
        return LINES[Math.max(1, Math.min(8, width)) - 1];
    }

    private static RenderType[] createLines() {
        RenderType[] result = new RenderType[8];
        for (int width = 1; width <= result.length; width++) {
            result[width - 1] = RenderType.create(
                    "voxelbridge_selection_lines_" + width,
                    DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES,
                    1536,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
        return result;
    }
}
