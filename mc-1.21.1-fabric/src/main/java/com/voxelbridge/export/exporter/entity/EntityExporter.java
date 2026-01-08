package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Set;

public final class EntityExporter {

    private EntityExporter() {}

    public static void exportEntitiesInChunk(
        ExportContext ctx,
        IrSink sceneSink,
        World level,
        Box bounds,
        double offsetX,
        double offsetY,
        double offsetZ,
        Set<Integer> processedEntityIds
    ) {
        if (level == null) return;

        java.util.List<Entity> candidates = new java.util.ArrayList<>();
        for (Entity entity : level.getOtherEntities(null, bounds, e -> true)) {
            if (entity == null || !entity.isAlive()) continue;
            if (!processedEntityIds.add(entity.getId())) {
                VoxelBridgeLogger.debug(LogModule.ENTITY,
                    "[EntityExporter] Skipping already exported entity: " + entity.getType() + " id=" + entity.getId());
                continue;
            }
            if (!shouldExport(entity)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY,
                    "[EntityExporter] Skipping filtered entity: " + entity.getType());
                continue;
            }
            candidates.add(entity);
        }

        if (candidates.isEmpty()) {
            return;
        }

        ctx.getMc().submitAndJoin(() -> {
            for (Entity entity : candidates) {
                Vec3d pos = entity.getPos();
                VoxelBridgeLogger.info(LogModule.ENTITY, String.format(
                    "Exporting entity: %s (%s) at [%.2f, %.2f, %.2f]",
                    entity.getName().getString(),
                    entity.getType(),
                    pos.x, pos.y, pos.z));
                Box bb = entity.getBoundingBox();
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[BBox] %s min[%.3f, %.3f, %.3f] max[%.3f, %.3f, %.3f] size[%.3f x %.3f x %.3f]",
                    entity.getType(),
                    bb.minX, bb.minY, bb.minZ,
                    bb.maxX, bb.maxY, bb.maxZ,
                    bb.maxX - bb.minX, bb.maxY - bb.minY, bb.maxZ - bb.minZ));

                boolean success = EntityRenderer.renderOnMainThread(ctx, entity, sceneSink, offsetX, offsetY, offsetZ);
                if (!success) {
                    VoxelBridgeLogger.warn(LogModule.ENTITY, String.format(
                        "[NoGeometry] %s at [%.2f, %.2f, %.2f] - %s",
                        entity.getType(),
                        pos.x, pos.y, pos.z,
                        "Render returned false"));
                }
            }
        });
    }

    private static boolean shouldExport(Entity entity) {
        if (entity instanceof MobEntity mob && !mob.isAiDisabled()) {
            return false;
        }
        return true;
    }
}
