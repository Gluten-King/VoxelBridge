package com.voxelbridge.export.exporter;

import com.voxelbridge.compat.BlockStateCompat;
import com.voxelbridge.core.util.geometry.QuadGeometryKey;
import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.pipeline.contract.Face;
import com.voxelbridge.pipeline.contract.OcclusionFacts;
import com.voxelbridge.pipeline.geometry.FaceCoveragePolicy;
import com.voxelbridge.pipeline.geometry.FaceInset;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Applies non-solid face de-duplication and small insets that avoid z-fighting.
 */
final class NonsolidGeometryCleaner {
    private static final float SAME_FACE_CELL = 3.0f;
    private static final float AABB_EPS = 1e-3f;
    private static final float FACE_EPS = 1e-4f;
    private static final float BOUNDARY_EPS = 5e-4f;
    private static final float NONSOLID_INSET = 5e-4f;

    private final Level level;
    private final ExportContext context;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final Int2ObjectOpenHashMap<ObjectOpenHashSet<SameFaceKey>> sameFaceBuckets =
        new Int2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<String> stringIds = new Object2IntOpenHashMap<>();
    private int nextStringId = 1;

    private record SameFaceKey(int blockId, int spriteId, int nx, int ny, int nz,
                               QuadGeometryKey geometry) {}

    NonsolidGeometryCleaner(ExportContext context, Level level, double offsetX, double offsetY, double offsetZ) {
        this.context = context;
        this.level = level;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        stringIds.defaultReturnValue(-1);
    }

    void clearBuckets() {
        sameFaceBuckets.clear();
    }

    boolean registerSameFaceKey(String blockKey, String spriteKey, float[] positions, float[] uv, float[] normal) {
        if (blockKey == null || spriteKey == null || positions == null || positions.length < 12) {
            return true;
        }
        float[] n = normalizeCanonical(normal);
        int[] nKey = quantizeNormalSigned(n);
        int blockId = getStringId(blockKey);
        int spriteId = getStringId(spriteKey);
        SameFaceKey key = new SameFaceKey(
            blockId, spriteId, nKey[0], nKey[1], nKey[2],
            QuadGeometryKey.of(spriteId, blockId, positions, null)
        );
        int[] bucket = bucketFor(positions);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int neighborKey = bucketKeyFor(bucket[0] + dx, bucket[1] + dy, bucket[2] + dz);
                    ObjectOpenHashSet<SameFaceKey> set = sameFaceBuckets.get(neighborKey);
                    if (set != null && set.contains(key)) {
                        return false;
                    }
                }
            }
        }
        int bucketKey = bucketKeyFor(bucket[0], bucket[1], bucket[2]);
        ObjectOpenHashSet<SameFaceKey> bucketSet = sameFaceBuckets.get(bucketKey);
        if (bucketSet == null) {
            bucketSet = new ObjectOpenHashSet<>();
            sameFaceBuckets.put(bucketKey, bucketSet);
        }
        bucketSet.add(key);
        return true;
    }

    boolean shouldCullAgainstSolid(BlockState state, BlockPos pos, QuadData quad, Direction dir) {
        if (state == null || quad == null || dir == null) {
            return false;
        }
        boolean boundaryFace = isBoundaryFaceLocal(quad, dir);
        boolean currentSolid = BlockStateCompat.isSolidRender(state, level, pos);
        if (!boundaryFace || currentSolid) return false;
        float[] quadAabb = new float[4];
        if (!getLocalFaceAabb(quad, dir, quadAabb)) {
            return false;
        }
        OcclusionFacts facts = context.session().runtime().world()
            .occlusion(pos.getX(), pos.getY(), pos.getZ(), face(dir));
        return FaceCoveragePolicy.shouldCullAgainstNeighbor(
            boundaryFace, currentSolid, facts, quadAabb, AABB_EPS);
    }

    void applyInsetAgainstNonSolid(BlockState state, BlockPos pos, QuadData quad, Direction dir, float[] positions) {
        if (state == null || quad == null || dir == null || positions == null || positions.length < 12) {
            return;
        }
        if (BlockStateCompat.isSolidRender(state, level, pos)) {
            return;
        }
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (BlockStateCompat.isSolidRender(neighborState, level, neighborPos)) {
            return;
        }
        if (neighborState.getBlock() == state.getBlock()) {
            return;
        }
        if (!isBoundaryFaceLocal(quad, dir)) {
            return;
        }
        float[] quadAabb = new float[4];
        if (!getLocalFaceAabb(quad, dir, quadAabb)) {
            return;
        }
        float[] neighborAabb = new float[4];
        if (!getNeighborFaceAabb(neighborState, neighborPos, dir.getOpposite(), neighborAabb)) {
            return;
        }
        if (!FaceCoveragePolicy.overlaps(quadAabb, neighborAabb, AABB_EPS)) {
            return;
        }
        String selfKey = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String otherKey = String.valueOf(BuiltInRegistries.BLOCK.getKey(neighborState.getBlock()));
        if (selfKey.compareTo(otherKey) <= 0) {
            return;
        }
        FaceInset.apply(positions, face(dir), NONSOLID_INSET);
    }

    void applyInsetAgainstSolid(Direction dir, float[] positions) {
        if (dir == null || positions == null || positions.length < 12) {
            return;
        }
        FaceInset.apply(positions, face(dir), NONSOLID_INSET);
    }

    Direction inferOutwardDirection(float[] positions, BlockPos pos) {
        if (positions == null || positions.length < 12 || pos == null) {
            return null;
        }
        float cx = (positions[0] + positions[3] + positions[6] + positions[9]) * 0.25f;
        float cy = (positions[1] + positions[4] + positions[7] + positions[10]) * 0.25f;
        float cz = (positions[2] + positions[5] + positions[8] + positions[11]) * 0.25f;

        float bx = (float) (pos.getX() + 0.5 + offsetX);
        float by = (float) (pos.getY() + 0.5 + offsetY);
        float bz = (float) (pos.getZ() + 0.5 + offsetZ);

        float dx = cx - bx;
        float dy = cy - by;
        float dz = cz - bz;

        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        float adz = Math.abs(dz);

        if (adx >= ady && adx >= adz) {
            return dx >= 0f ? Direction.EAST : Direction.WEST;
        }
        if (ady >= adx && ady >= adz) {
            return dy >= 0f ? Direction.UP : Direction.DOWN;
        }
        return dz >= 0f ? Direction.SOUTH : Direction.NORTH;
    }

    private static int[] quantizeNormalSigned(float[] normal) {
        float nx = normal[0];
        float ny = normal[1];
        float nz = normal[2];
        return new int[] {
            Math.round(nx * 1000f),
            Math.round(ny * 1000f),
            Math.round(nz * 1000f)
        };
    }

    private static float[] normalizeCanonical(float[] normal) {
        if (normal == null || normal.length < 3) {
            return new float[] {0f, 1f, 0f};
        }
        float lenSq = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
        if (lenSq < 1e-8f) {
            return new float[] {0f, 1f, 0f};
        }
        float inv = 1f / (float) Math.sqrt(lenSq);
        float nx = normal[0] * inv;
        float ny = normal[1] * inv;
        float nz = normal[2] * inv;
        if (nx < 0f || (nx == 0f && ny < 0f) || (nx == 0f && ny == 0f && nz < 0f)) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return new float[] {nx, ny, nz};
    }

    private static int[] bucketFor(float[] positions) {
        float cx = (positions[0] + positions[3] + positions[6] + positions[9]) * 0.25f;
        float cy = (positions[1] + positions[4] + positions[7] + positions[10]) * 0.25f;
        float cz = (positions[2] + positions[5] + positions[8] + positions[11]) * 0.25f;
        int bx = floorDiv(cx, SAME_FACE_CELL);
        int by = floorDiv(cy, SAME_FACE_CELL);
        int bz = floorDiv(cz, SAME_FACE_CELL);
        return new int[] { bx, by, bz };
    }

    private static int bucketKeyFor(int bx, int by, int bz) {
        long packed = packInt3(bx, by, bz);
        return foldLongToInt(packed);
    }

    private static long packInt3(int x, int y, int z) {
        long lx = ((long) x) & 0x1FFFFF;
        long ly = ((long) y) & 0x1FFFFF;
        long lz = ((long) z) & 0x1FFFFF;
        return (lx << 42) | (ly << 21) | lz;
    }

    private static int foldLongToInt(long value) {
        return (int) (value ^ (value >>> 32));
    }

    private int getStringId(String value) {
        if (value == null) {
            return 0;
        }
        int id = stringIds.getInt(value);
        if (id != -1) {
            return id;
        }
        id = nextStringId++;
        stringIds.put(value, id);
        return id;
    }

    private static int floorDiv(float value, float size) {
        return (int) Math.floor(value / size);
    }

    private static boolean getLocalFaceAabb(QuadData quad, Direction dir, float[] out) {
        int[] verts = quad.vertices();
        if (verts == null || verts.length < 32 || out == null || out.length < 4) {
            return false;
        }
        Direction.Axis axis = dir.getAxis();
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float x = Float.intBitsToFloat(verts[base]);
            float y = Float.intBitsToFloat(verts[base + 1]);
            float z = Float.intBitsToFloat(verts[base + 2]);
            float u;
            float v;
            if (axis == Direction.Axis.X) {
                u = y;
                v = z;
            } else if (axis == Direction.Axis.Y) {
                u = x;
                v = z;
            } else {
                u = x;
                v = y;
            }
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
        }
        out[0] = minU;
        out[1] = maxU;
        out[2] = minV;
        out[3] = maxV;
        return true;
    }

    private static boolean isBoundaryFaceLocal(QuadData quad, Direction dir) {
        int[] verts = quad.vertices();
        if (verts == null || verts.length < 32) {
            return false;
        }
        Direction.Axis axis = dir.getAxis();
        float target = dir.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0f : 1f;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float v;
            if (axis == Direction.Axis.X) {
                v = Float.intBitsToFloat(verts[base]);
            } else if (axis == Direction.Axis.Y) {
                v = Float.intBitsToFloat(verts[base + 1]);
            } else {
                v = Float.intBitsToFloat(verts[base + 2]);
            }
            if (Math.abs(v - target) > BOUNDARY_EPS) {
                return false;
            }
        }
        return true;
    }

    private static Face face(Direction direction) {
        return switch (direction) {
            case DOWN -> Face.DOWN;
            case UP -> Face.UP;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case WEST -> Face.WEST;
            case EAST -> Face.EAST;
        };
    }

    private boolean getNeighborFaceAabb(BlockState state, BlockPos pos, Direction face, float[] out) {
        if (out == null || out.length < 4) {
            return false;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        boolean found = false;
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        Direction.Axis axis = face.getAxis();
        boolean negative = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE;

        for (AABB box : shape.toAabbs()) {
            if (axis == Direction.Axis.X) {
                if (negative) {
                    if (box.minX > FACE_EPS) continue;
                } else if (box.maxX < 1.0 - FACE_EPS) {
                    continue;
                }
                minU = Math.min(minU, (float) box.minY);
                maxU = Math.max(maxU, (float) box.maxY);
                minV = Math.min(minV, (float) box.minZ);
                maxV = Math.max(maxV, (float) box.maxZ);
            } else if (axis == Direction.Axis.Y) {
                if (negative) {
                    if (box.minY > FACE_EPS) continue;
                } else if (box.maxY < 1.0 - FACE_EPS) {
                    continue;
                }
                minU = Math.min(minU, (float) box.minX);
                maxU = Math.max(maxU, (float) box.maxX);
                minV = Math.min(minV, (float) box.minZ);
                maxV = Math.max(maxV, (float) box.maxZ);
            } else {
                if (negative) {
                    if (box.minZ > FACE_EPS) continue;
                } else if (box.maxZ < 1.0 - FACE_EPS) {
                    continue;
                }
                minU = Math.min(minU, (float) box.minX);
                maxU = Math.max(maxU, (float) box.maxX);
                minV = Math.min(minV, (float) box.minY);
                maxV = Math.max(maxV, (float) box.maxY);
            }
            found = true;
        }

        if (!found) {
            return false;
        }
        out[0] = minU;
        out[1] = maxU;
        out[2] = minV;
        out[3] = maxV;
        return true;
    }
}
