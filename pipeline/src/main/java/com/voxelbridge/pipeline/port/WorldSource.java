package com.voxelbridge.pipeline.port;

import com.voxelbridge.pipeline.contract.BlockSample;
import com.voxelbridge.pipeline.contract.OcclusionFacts;
import com.voxelbridge.pipeline.contract.Face;
import com.voxelbridge.pipeline.contract.Region3i;

public interface WorldSource {
    WorldSource EMPTY = new WorldSource() {
        @Override
        public void visitBlocks(Region3i region, BlockVisitor visitor) {
        }

        @Override
        public OcclusionFacts occlusion(int blockX, int blockY, int blockZ, Face face) {
            return OcclusionFacts.unknown();
        }
    };

    void visitBlocks(Region3i region, BlockVisitor visitor);

    /**
     * Returns the neighboring-face facts seen from the supplied block position.
     * Coordinates stay primitive because this method is called for every candidate face.
     */
    OcclusionFacts occlusion(int blockX, int blockY, int blockZ, Face face);

    default OcclusionFacts occlusion(BlockSample block, Face face) {
        if (block == null) return OcclusionFacts.unknown();
        return occlusion(block.position().x(), block.position().y(), block.position().z(), face);
    }

    @FunctionalInterface
    interface BlockVisitor {
        void visit(BlockSample block);
    }
}
