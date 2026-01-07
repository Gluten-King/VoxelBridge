package com.voxelbridge.client;

import com.voxelbridge.export.ExportControl;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.ExportProgressTracker.ChunkState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * Renders selection boxes and chunk progress overlay during world render.
 */
public final class SelectionRenderer {

    private SelectionRenderer() {}

    public static void onRenderLevel(WorldRenderContext context) {
        BlockPos pos1 = ExportControl.getPos1();
        BlockPos pos2 = ExportControl.getPos2();
        if (pos1 == null && pos2 == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d camPos = context.camera().getPos();
        var matrices = context.matrixStack();
        VertexConsumerProvider provider = context.consumers();
        var consumer = provider.getBuffer(RenderLayer.getLines());

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        if (pos1 != null) renderBox(matrices, consumer, pos1, 1.0f, 0.2f, 0.2f, 0.6f);
        if (pos2 != null) renderBox(matrices, consumer, pos2, 0.2f, 0.2f, 1.0f, 0.6f);
        if (pos1 != null && pos2 != null) {
            renderSelectionBox(matrices, consumer, pos1, pos2, 0.0f, 1.0f, 1.0f, 0.5f);
            renderChunkStatus(matrices, consumer, pos1, pos2);
        }

        matrices.pop();
    }

    private static void renderBox(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumer consumer,
                                  BlockPos pos, float r, float g, float b, float a) {
        Box box = new Box(pos).expand(0.002);
        WorldRenderer.drawBox(matrices, consumer, box, r, g, b, a);
    }

    private static void renderSelectionBox(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumer consumer,
                                           BlockPos pos1, BlockPos pos2,
                                           float r, float g, float b, float a) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        Box box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        WorldRenderer.drawBox(matrices, consumer, box, r, g, b, a);
    }

    private static void renderChunkStatus(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.VertexConsumer consumer,
                                          BlockPos pos1, BlockPos pos2) {
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
            int chunkX = ChunkPos.getPackedX(key);
            int chunkZ = ChunkPos.getPackedZ(key);
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

            float r, g, b;
            ChunkState state = entry.getValue();
            if (state == ChunkState.DONE) {
                r = 0.1f; g = 1.0f; b = 0.1f;
            } else if (state == ChunkState.RUNNING) {
                r = 1.0f; g = 0.8f; b = 0.1f;
            } else {
                r = 1.0f; g = 0.2f; b = 0.2f;
            }

            Box chunkBox = new Box(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ);
            WorldRenderer.drawBox(matrices, consumer, chunkBox, r, g, b, 0.35f);
        }
    }

}
