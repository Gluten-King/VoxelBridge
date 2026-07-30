package com.voxelbridge.export.exporter;

import com.voxelbridge.compat.BlockStateCompat;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.quad.QuadData;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
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
    private static final float SAME_FACE_QUANT = 1000f;
    private static final float SAME_FACE_CELL = 3.0f;
    private static final float AABB_EPS = 1e-3f;
    private static final float FACE_EPS = 1e-4f;
    private static final float BOUNDARY_EPS = 5e-4f;
    private static final float NONSOLID_INSET = 5e-4f;

    private final Level level;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final Int2ObjectOpenHashMap<LongOpenHashSet> sameFaceBuckets = new Int2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<String> stringIds = new Object2IntOpenHashMap<>();
    private int nextStringId = 1;

    NonsolidGeometryCleaner(Level level, double offsetX, double offsetY, double offsetZ) {
        this.level = level;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        stringIds.defaultReturnValue(-1);
    }

    void clearBuckets() {
        sameFaceBuckets.clear();
    }

    boolean shouldCullSameNonSolidFace(Direction dir) {
        return dir == Direction.EAST || dir == Direction.SOUTH || dir == Direction.UP;
    }

    boolean isSameNonSolidNeighborFace(BlockState state, BlockPos pos, QuadData quad, Direction dir) {
        BlockPos neighbor = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighbor);
        if (neighborState.getBlock() != state.getBlock()) {
            return false;
        }
        if (BlockStateCompat.isSolidRender(neighborState, level, neighbor)) {
            return false;
        }
        float[] quadAabb = new float[4];
        if (!getLocalFaceAabb(quad, dir, quadAabb)) {
            return false;
        }
        float[] neighborAabb = new float[4];
        if (!getNeighborFaceAabb(neighborState, neighbor, dir.getOpposite(), neighborAabb)) {
            return false;
        }
        return aabbApproxEqual(quadAabb, neighborAabb);
    }

    boolean registerSameFaceKey(String blockKey, String spriteKey, float[] positions, float[] uv, float[] normal) {
        if (blockKey == null || spriteKey == null || positions == null || positions.length < 12) {
            return true;
        }
        float[] uvAabb = new float[4];
        if (!GeometryUtil.computeUvBounds(uv, uvAabb)) {
            uvAabb[0] = 0f;
            uvAabb[1] = 0f;
            uvAabb[2] = 0f;
            uvAabb[3] = 0f;
        }
        float[] n = normalizeCanonical(normal);
        int[] nKey = quantizeNormalSigned(n);
        int plane = quantizePlane(positions, n);
        float[] faceAabb = projectAabb2d(positions, n);
        int cx = Math.round((positions[0] + positions[3] + positions[6] + positions[9]) * 0.25f * SAME_FACE_QUANT);
        int cy = Math.round((positions[1] + positions[4] + positions[7] + positions[10]) * 0.25f * SAME_FACE_QUANT);
        int cz = Math.round((positions[2] + positions[5] + positions[8] + positions[11]) * 0.25f * SAME_FACE_QUANT);
        long key = hashSameFaceKey(
            getStringId(blockKey),
            getStringId(spriteKey),
            nKey[0], nKey[1], nKey[2],
            plane,
            cx, cy, cz,
            Math.round(faceAabb[0] * SAME_FACE_QUANT),
            Math.round(faceAabb[1] * SAME_FACE_QUANT),
            Math.round(faceAabb[2] * SAME_FACE_QUANT),
            Math.round(faceAabb[3] * SAME_FACE_QUANT),
            Math.round(uvAabb[0] * SAME_FACE_QUANT),
            Math.round(uvAabb[1] * SAME_FACE_QUANT),
            Math.round(uvAabb[2] * SAME_FACE_QUANT),
            Math.round(uvAabb[3] * SAME_FACE_QUANT)
        );
        int[] bucket = bucketFor(positions);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int neighborKey = bucketKeyFor(bucket[0] + dx, bucket[1] + dy, bucket[2] + dz);
                    LongOpenHashSet set = sameFaceBuckets.get(neighborKey);
                    if (set != null && set.contains(key)) {
                        return false;
                    }
                }
            }
        }
        int bucketKey = bucketKeyFor(bucket[0], bucket[1], bucket[2]);
        LongOpenHashSet bucketSet = sameFaceBuckets.get(bucketKey);
        if (bucketSet == null) {
            bucketSet = new LongOpenHashSet();
            sameFaceBuckets.put(bucketKey, bucketSet);
        }
        bucketSet.add(key);
        return true;
    }

    boolean shouldCullAgainstSolid(BlockState state, BlockPos pos, QuadData quad, Direction dir) {
        if (state == null || quad == null || dir == null) {
            return false;
        }
        if (!isBoundaryFaceLocal(quad, dir)) {
            return false;
        }
        if (BlockStateCompat.isSolidRender(state, level, pos)) {
            return false;
        }
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (!BlockStateCompat.isSolidRender(neighborState, level, neighborPos)) {
            return false;
        }
        float[] quadAabb = new float[4];
        if (!getLocalFaceAabb(quad, dir, quadAabb)) {
            return false;
        }
        float[] neighborAabb = new float[4];
        if (!getNeighborFaceAabb(neighborState, neighborPos, dir.getOpposite(), neighborAabb)) {
            return false;
        }
        return aabbContains(neighborAabb, quadAabb);
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
        if (!aabbOverlaps(quadAabb, neighborAabb)) {
            return;
        }
        String selfKey = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String otherKey = String.valueOf(BuiltInRegistries.BLOCK.getKey(neighborState.getBlock()));
        if (selfKey.compareTo(otherKey) <= 0) {
            return;
        }
        float dx = -dir.getStepX() * NONSOLID_INSET;
        float dy = -dir.getStepY() * NONSOLID_INSET;
        float dz = -dir.getStepZ() * NONSOLID_INSET;
        for (int i = 0; i < 4; i++) {
            positions[i * 3] += dx;
            positions[i * 3 + 1] += dy;
            positions[i * 3 + 2] += dz;
        }
    }

    void applyInsetAgainstSolid(Direction dir, float[] positions) {
        if (dir == null || positions == null || positions.length < 12) {
            return;
        }
        float dx = -dir.getStepX() * NONSOLID_INSET;
        float dy = -dir.getStepY() * NONSOLID_INSET;
        float dz = -dir.getStepZ() * NONSOLID_INSET;
        for (int i = 0; i < 4; i++) {
            positions[i * 3] += dx;
            positions[i * 3 + 1] += dy;
            positions[i * 3 + 2] += dz;
        }
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

    private static int quantizePlane(float[] positions, float[] normal) {
        float cx = (positions[0] + positions[3] + positions[6] + positions[9]) * 0.25f;
        float cy = (positions[1] + positions[4] + positions[7] + positions[10]) * 0.25f;
        float cz = (positions[2] + positions[5] + positions[8] + positions[11]) * 0.25f;
        float nx = normal[0];
        float ny = normal[1];
        float nz = normal[2];
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            return 0;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        float d = nx * cx + ny * cy + nz * cz;
        return Math.round(d * SAME_FACE_QUANT);
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

    private static long hashSameFaceKey(int blockId, int spriteId,
                                        int nx, int ny, int nz, int plane,
                                        int cx, int cy, int cz,
                                        int minU, int maxU, int minV, int maxV,
                                        int uvMinU, int uvMaxU, int uvMinV, int uvMaxV) {
        long h = 1469598103934665603L;
        h = (h ^ blockId) * 1099511628211L;
        h = (h ^ spriteId) * 1099511628211L;
        h = (h ^ nx) * 1099511628211L;
        h = (h ^ ny) * 1099511628211L;
        h = (h ^ nz) * 1099511628211L;
        h = (h ^ plane) * 1099511628211L;
        h = (h ^ cx) * 1099511628211L;
        h = (h ^ cy) * 1099511628211L;
        h = (h ^ cz) * 1099511628211L;
        h = (h ^ minU) * 1099511628211L;
        h = (h ^ maxU) * 1099511628211L;
        h = (h ^ minV) * 1099511628211L;
        h = (h ^ maxV) * 1099511628211L;
        h = (h ^ uvMinU) * 1099511628211L;
        h = (h ^ uvMaxU) * 1099511628211L;
        h = (h ^ uvMinV) * 1099511628211L;
        h = (h ^ uvMaxV) * 1099511628211L;
        return h;
    }

    private static int floorDiv(float value, float size) {
        return (int) Math.floor(value / size);
    }

    private static float[] projectAabb2d(float[] positions, float[] normal) {
        float anx = Math.abs(normal[0]);
        float any = Math.abs(normal[1]);
        float anz = Math.abs(normal[2]);
        int axis;
        if (anx >= any && anx >= anz) {
            axis = 0;
        } else if (any >= anz) {
            axis = 1;
        } else {
            axis = 2;
        }

        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = positions[i * 3];
            float y = positions[i * 3 + 1];
            float z = positions[i * 3 + 2];
            float u;
            float v;
            if (axis == 0) {
                u = y;
                v = z;
            } else if (axis == 1) {
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
        return new float[]{minU, maxU, minV, maxV};
    }

    private static boolean aabbApproxEqual(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) <= AABB_EPS
            && Math.abs(a[1] - b[1]) <= AABB_EPS
            && Math.abs(a[2] - b[2]) <= AABB_EPS
            && Math.abs(a[3] - b[3]) <= AABB_EPS;
    }

    private static boolean aabbContains(float[] container, float[] inner) {
        return container[0] - AABB_EPS <= inner[0]
            && container[1] + AABB_EPS >= inner[1]
            && container[2] - AABB_EPS <= inner[2]
            && container[3] + AABB_EPS >= inner[3];
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

    private static boolean aabbOverlaps(float[] a, float[] b) {
        return a[1] + AABB_EPS > b[0]
            && a[0] - AABB_EPS < b[1]
            && a[3] + AABB_EPS > b[2]
            && a[2] - AABB_EPS < b[3];
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
