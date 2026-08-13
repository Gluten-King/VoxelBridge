package com.voxelbridge.adapter;

import net.minecraft.client.renderer.RenderPipelines;
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
                    1536,
                    RenderPipelines.LINES,
                    RenderType.CompositeState.builder()
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                            .createCompositeState(false));
        }
        return result;
    }
}
