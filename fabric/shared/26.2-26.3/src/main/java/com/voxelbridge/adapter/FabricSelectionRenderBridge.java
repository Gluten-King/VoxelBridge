package com.voxelbridge.adapter;

import com.voxelbridge.export.ExportControl;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.ExportProgressTracker.ChunkState;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Fabric selection renderer using MC 26.2 gizmo submit API.
 * <p>
 * DrawableGizmoPrimitives.render(pose, buffer, camera, matrix) was replaced by
 * submit(SubmitNodeCollector, CameraRenderState, boolean).
 */
public final class FabricSelectionRenderBridge implements SelectionRenderBridge {

    @Override
    public void register(Object gameBus) {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(this::onRenderWorld);
    }

    private void onRenderWorld(LevelRenderContext context) {
        BlockPos pos1 = ExportControl.getPos1();
        BlockPos pos2 = ExportControl.getPos2();
        if (pos1 == null && pos2 == null) {
            return;
        }

        DrawableGizmoPrimitives primitives = new DrawableGizmoPrimitives();

        if (pos1 != null) {
            emitBox(primitives, new AABB(pos1).inflate(0.002), 0xFFFF3333);
        }
        if (pos2 != null) {
            emitBox(primitives, new AABB(pos2).inflate(0.002), 0xFF3333FF);
        }
        if (pos1 != null && pos2 != null) {
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
            int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
            int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
            emitBox(primitives, new AABB(minX, minY, minZ, maxX, maxY, maxZ), 0xFF00FFFF);

            emitChunkStatus(primitives, pos1, pos2);
            emitProgressLabel(primitives, pos1, pos2);
        }

        var cameraState = context.levelState().cameraRenderState;
        // Always submit; DrawableGizmoPrimitives.submit no-ops when empty.
        primitives.submit(context.submitNodeCollector(), cameraState, false);
    }

    private static void emitBox(GizmoPrimitives p, AABB box, int color) {
        double x0 = box.minX, y0 = box.minY, z0 = box.minZ;
        double x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
        Vec3[] c = {
            new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0),
            new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
        };
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] e : edges) {
            p.addLine(c[e[0]], c[e[1]], color, 2.0f);
        }
    }

    private static void emitChunkStatus(GizmoPrimitives p, BlockPos pos1, BlockPos pos2) {
        var states = ExportProgressTracker.snapshot();
        if (states.isEmpty()) {
            return;
        }

        int selMinX = Math.min(pos1.getX(), pos2.getX());
        int selMinY = Math.min(pos1.getY(), pos2.getY());
        int selMinZ = Math.min(pos1.getZ(), pos2.getZ());
        int selMaxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int selMaxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int selMaxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        for (Map.Entry<Long, ChunkState> entry : states.entrySet()) {
            long key = entry.getKey();
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            int maxX = minX + 16;
            int maxZ = minZ + 16;

            int boxMinX = Math.max(minX, selMinX);
            int boxMinY = selMinY;
            int boxMinZ = Math.max(minZ, selMinZ);
            int boxMaxX = Math.min(maxX, selMaxX);
            int boxMaxY = selMaxY;
            int boxMaxZ = Math.min(maxZ, selMaxZ);

            if (boxMinX >= boxMaxX || boxMinY >= boxMaxY || boxMinZ >= boxMaxZ) {
                continue;
            }

            int color;
            ChunkState state = entry.getValue();
            if (state == ChunkState.DONE) {
                color = 0xFF19E619;
            } else if (state == ChunkState.RUNNING) {
                color = 0xFFFFCC19;
            } else {
                color = 0xFFFF3333;
            }
            color = ARGB.multiplyAlpha(color, 0.35f);
            emitBox(p, new AABB(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ), color);
        }
    }

    private static void emitProgressLabel(GizmoPrimitives p, BlockPos pos1, BlockPos pos2) {
        ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
        if (progress.total() <= 0) {
            return;
        }
        String text = String.format("Export: %d/%d (%.1f%%)", progress.done(), progress.total(), progress.percent());
        double cx = (pos1.getX() + pos2.getX() + 1) * 0.5;
        double cy = (pos1.getY() + pos2.getY() + 1) * 0.5 + 1.5;
        double cz = (pos1.getZ() + pos2.getZ() + 1) * 0.5;
        TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(0xFFFFFFFF);
        p.addText(new Vec3(cx, cy, cz), text, style);
    }
}
