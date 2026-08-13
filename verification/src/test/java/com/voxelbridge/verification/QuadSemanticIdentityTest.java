package com.voxelbridge.verification;

import com.voxelbridge.core.ir.QuadSemantic;

/** Dependency-free regression tests for block-type glTF grouping. */
public final class QuadSemanticIdentityTest {

    private QuadSemanticIdentityTest() {}

    public static void main(String[] args) {
        QuadSemantic leavesNear = terrain(
            "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
        );
        QuadSemantic leavesFar = terrain(
            "minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]"
        );

        QuadSemantic nearType = leavesNear.typeIdentity();
        QuadSemantic farType = leavesFar.typeIdentity();
        require(nearType.equals(farType),
            "BlockState variants of one block type must share a glTF identity");
        require(nearType.blockState() == null,
            "BlockState properties must not leak into block-type grouping");
        require("minecraft:oak_leaves".equals(nearType.blockId()),
            "The stable block registry id must be retained");

        QuadSemantic birch = new QuadSemantic(
            "terrain", "minecraft:birch_leaves", "minecraft:birch_leaves",
            "minecraft:birch_leaves[distance=1,persistent=false]",
            null, null, null, null, null, false, -1
        );
        require(!nearType.equals(birch.typeIdentity()),
            "Different block types must remain in different glTF identities");
    }

    private static QuadSemantic terrain(String blockState) {
        return new QuadSemantic(
            "terrain", "minecraft:oak_leaves", "minecraft:oak_leaves", blockState,
            null, null, null, null, null, false, -1
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
