package com.voxelbridge.export.quad;

import net.minecraft.client.renderer.block.model.BakedQuad;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/**
 * Utilities for adapting quad sources without copying vertex data.
 */
public final class QuadDataUtil {
    private QuadDataUtil() {}

    public static List<QuadData> wrapBakedQuads(List<BakedQuad> quads) {
        if (quads == null || quads.isEmpty()) {
            return Collections.emptyList();
        }
        return new BakedQuadDataList(quads);
    }

    private static final class BakedQuadDataList extends AbstractList<QuadData> {
        private final List<BakedQuad> quads;
        private final QuadData[] cache;

        private BakedQuadDataList(List<BakedQuad> quads) {
            this.quads = quads;
            this.cache = new QuadData[quads.size()];
        }

        @Override
        public QuadData get(int index) {
            QuadData data = cache[index];
            if (data == null) {
                data = new BakedQuadData(quads.get(index));
                cache[index] = data;
            }
            return data;
        }

        @Override
        public int size() {
            return quads.size();
        }
    }
}
