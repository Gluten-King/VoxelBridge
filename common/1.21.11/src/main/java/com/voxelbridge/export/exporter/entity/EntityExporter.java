package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
public final class EntityExporter {

    private EntityExporter() {}

    public static void exportEntitiesInChunk(
        ExportContext ctx,
        IrSink sceneSink,
        Level level,
        int chunkX,
        int chunkZ,
        AABB bounds,
        double offsetX,
        double offsetY,
        double offsetZ,
        Set<Integer> processedEntityIds
    ) {
        if (level == null) return;

        java.util.List<Entity> candidates = new java.util.ArrayList<>();
        for (Entity entity : level.getEntities(null, bounds)) {
            if (entity == null || entity.isRemoved()) continue;
            if (!processedEntityIds.add(entity.getId())) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityExporter] Skipping already exported entity: " + entity.getType() + " id=" + entity.getId());
                continue; // already exported via another chunk
            }
            if (!shouldExport(entity)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityExporter] Skipping filtered entity: " + entity.getType());
                continue;
            }
            candidates.add(entity);
        }

        if (candidates.isEmpty()) {
            return;
        }

        ctx.getMc().executeBlocking(() -> {
            EntityRenderer.clearChunkTracker(chunkX, chunkZ);
            for (Entity entity : candidates) {
                Vec3 pos = entity.position();
                VoxelBridgeLogger.info(LogModule.ENTITY, String.format("Exporting entity: %s (%s) at [%.2f, %.2f, %.2f]",
                    entity.getName().getString(),
                        entity.getType(),
                    pos.x, pos.y, pos.z));
                net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
                VoxelBridgeLogger.debug(LogModule.ENTITY, String.format(
                    "[BBox] %s min[%.3f, %.3f, %.3f] max[%.3f, %.3f, %.3f] size[%.3f x %.3f x %.3f]",
                    entity.getType(),
                    bb.minX, bb.minY, bb.minZ,
                    bb.maxX, bb.maxY, bb.maxZ,
                    bb.maxX - bb.minX, bb.maxY - bb.minY, bb.maxZ - bb.minZ));

                boolean success = EntityRenderer.renderOnMainThread(ctx, entity, sceneSink, offsetX, offsetY, offsetZ);
                if (!success) {
                    VoxelBridgeLogger.warn(LogModule.ENTITY, String.format("[NoGeometry] %s at [%.2f, %.2f, %.2f] - %s",
                        entity.getType(),
                        pos.x, pos.y, pos.z,
                        "Render returned false"));
                }
            }
        });
    }

    private static boolean shouldExport(Entity entity) {
        // Filter: skip living entities that still have AI enabled.
        if (entity instanceof net.minecraft.world.entity.Mob mob && !mob.isNoAi()) {
            return false;
        }
        // Players are allowed for export (model + cape/elytra render layers).
        return true;
    }
}
