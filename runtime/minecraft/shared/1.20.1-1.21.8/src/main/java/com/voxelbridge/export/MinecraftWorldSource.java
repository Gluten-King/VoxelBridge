package com.voxelbridge.export;

import com.voxelbridge.compat.BlockStateCompat;
import com.voxelbridge.pipeline.contract.BlockPos3i;
import com.voxelbridge.pipeline.contract.BlockSample;
import com.voxelbridge.pipeline.contract.Face;
import com.voxelbridge.pipeline.contract.OcclusionFacts;
import com.voxelbridge.pipeline.contract.Region3i;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.port.WorldSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Normalizes exact-runtime world and occlusion facts for the stable pipeline contract. */
final class MinecraftWorldSource implements WorldSource {
    private static final float FACE_EPSILON = 1.0e-4f;
    private final Level level;

    MinecraftWorldSource(Level level) {
        if (level == null) throw new IllegalArgumentException("Client level is required");
        this.level = level;
    }

    @Override
    public void visitBlocks(Region3i region, BlockVisitor visitor) {
        if (region == null || visitor == null) return;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = region.min().y(); y <= region.max().y(); y++) {
            for (int z = region.min().z(); z <= region.max().z(); z++) {
                for (int x = region.min().x(); x <= region.max().x(); x++) {
                    position.set(x, y, z);
                    BlockState state = level.getBlockState(position);
                    if (state.isAir()) continue;
                    var offset = BlockStateCompat.getOffset(state, level, position);
                    visitor.visit(new BlockSample(
                        new BlockPos3i(x, y, z),
                        ResourceId.parse(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
                        state.getLightEmission(),
                        (float) offset.x, (float) offset.y, (float) offset.z));
                }
            }
        }
    }

    @Override
    public OcclusionFacts occlusion(int blockX, int blockY, int blockZ, Face face) {
        if (face == null || face == Face.NONE) return OcclusionFacts.unknown();
        BlockPos position = new BlockPos(blockX, blockY, blockZ);
        Direction direction = direction(face);
        BlockPos neighborPosition = position.relative(direction);
        if (!level.hasChunkAt(neighborPosition)) return OcclusionFacts.unknown();

        BlockState neighbor = level.getBlockState(neighborPosition);
        boolean solid = BlockStateCompat.isSolidRender(neighbor, level, neighborPosition);
        if (!solid) return new OcclusionFacts(true, true, false, false, null);

        List<AABB> boxes = neighbor.getShape(level, neighborPosition).toAabbs();
        float[] rectangles = new float[boxes.size() * 4];
        int count = 0;
        boolean full = false;
        Direction touchingFace = direction.getOpposite();
        for (AABB box : boxes) {
            if (!touches(box, touchingFace)) continue;
            int start = count;
            count = project(box, touchingFace, rectangles, count);
            full |= rectangles[start] <= FACE_EPSILON && rectangles[start + 1] >= 1f - FACE_EPSILON
                && rectangles[start + 2] <= FACE_EPSILON && rectangles[start + 3] >= 1f - FACE_EPSILON;
        }
        if (count != rectangles.length) rectangles = java.util.Arrays.copyOf(rectangles, count);
        // Until the exact-version adapter supplies Minecraft's own shouldRenderFace result,
        // keep vanillaVisible=true so pipeline policies remain conservative.
        return new OcclusionFacts(true, true, true, full, rectangles);
    }

    private static boolean touches(AABB box, Direction face) {
        return switch (face) {
            case WEST -> box.minX <= FACE_EPSILON;
            case EAST -> box.maxX >= 1.0 - FACE_EPSILON;
            case DOWN -> box.minY <= FACE_EPSILON;
            case UP -> box.maxY >= 1.0 - FACE_EPSILON;
            case NORTH -> box.minZ <= FACE_EPSILON;
            case SOUTH -> box.maxZ >= 1.0 - FACE_EPSILON;
        };
    }

    private static int project(AABB box, Direction face, float[] target, int offset) {
        switch (face.getAxis()) {
            case X -> {
                target[offset++] = (float) box.minY;
                target[offset++] = (float) box.maxY;
                target[offset++] = (float) box.minZ;
                target[offset++] = (float) box.maxZ;
            }
            case Y -> {
                target[offset++] = (float) box.minX;
                target[offset++] = (float) box.maxX;
                target[offset++] = (float) box.minZ;
                target[offset++] = (float) box.maxZ;
            }
            case Z -> {
                target[offset++] = (float) box.minX;
                target[offset++] = (float) box.maxX;
                target[offset++] = (float) box.minY;
                target[offset++] = (float) box.maxY;
            }
        }
        return offset;
    }

    private static Direction direction(Face face) {
        return switch (face) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            case NONE -> throw new IllegalArgumentException("Face.NONE has no runtime direction");
        };
    }
}
