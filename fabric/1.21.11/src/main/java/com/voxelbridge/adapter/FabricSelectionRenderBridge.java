package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.ExportProgressTracker.ChunkState;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.Map;

/**
 * Fabric 1.21.1 selection renderer using Fabric API events.
 */
public final class FabricSelectionRenderBridge implements SelectionRenderBridge {

    @Override
    public void register(Object gameBus) {
        // In Fabric, we ignore gameBus and use Fabric API directly
        WorldRenderEvents.END_MAIN.register(this::onRenderWorld);
    }

    private void onRenderWorld(WorldRenderContext context) {
        BlockPos pos1 = ExportControl.getPos1();
        BlockPos pos2 = ExportControl.getPos2();

        if (pos1 == null && pos2 == null) {
            return;
        }

        Minecraft mc = ClientAccessHolder.get().getMinecraft();
        Vec3 camPos = context.worldState().cameraRenderState.pos;
        PoseStack poseStack = context.matrices();
        MultiBufferSource consumers = context.consumers();
        VertexConsumer consumer = consumers.getBuffer(RenderTypes.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        if (pos1 != null) {
            renderBox(poseStack, consumer, pos1, 1.0f, 0.2f, 0.2f, 0.6f);
        }

        if (pos2 != null) {
            renderBox(poseStack, consumer, pos2, 0.2f, 0.2f, 1.0f, 0.6f);
        }

        if (pos1 != null && pos2 != null) {
            renderSelectionBox(poseStack, consumer, pos1, pos2, 0.0f, 1.0f, 1.0f, 0.5f);
            renderChunkStatus(poseStack, consumer, pos1, pos2);
            renderProgressLabel(poseStack, mc, pos1, pos2, consumers);
        }

        poseStack.popPose();
        if (consumers instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(RenderTypes.lines());
        }
    }

    private static void renderBox(PoseStack poseStack, VertexConsumer consumer,
            BlockPos pos, float r, float g, float b, float a) {
        AABB box = new AABB(pos).inflate(0.002);
        renderLineBox(poseStack, consumer, box, r, g, b, a);
    }

    private static void renderSelectionBox(PoseStack poseStack, VertexConsumer consumer,
            BlockPos pos1, BlockPos pos2,
            float r, float g, float b, float a) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        renderLineBox(poseStack, consumer, box, r, g, b, a);
    }

    private static void renderChunkStatus(PoseStack poseStack, VertexConsumer consumer,
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

            float r, g, b;
            ChunkState state = entry.getValue();
            if (state == ChunkState.DONE) {
                r = 0.1f;
                g = 1.0f;
                b = 0.1f;
            } else if (state == ChunkState.RUNNING) {
                r = 1.0f;
                g = 0.8f;
                b = 0.1f;
            } else {
                r = 1.0f;
                g = 0.2f;
                b = 0.2f;
            }

            AABB chunkBox = new AABB(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ);
            renderLineBox(poseStack, consumer, chunkBox, r, g, b, 0.35f);
        }
    }

    private static void renderProgressLabel(PoseStack poseStack, Minecraft mc,
            BlockPos pos1, BlockPos pos2,
            MultiBufferSource bufferSource) {
        ExportProgressTracker.Progress p = ExportProgressTracker.progress();
        if (p.total() <= 0) {
            return;
        }
        String text = String.format("Export: %d/%d (%.1f%%)", p.done(), p.total(), p.percent());

        double cx = (pos1.getX() + pos2.getX() + 1) * 0.5;
        double cy = (pos1.getY() + pos2.getY() + 1) * 0.5 + 1.5;
        double cz = (pos1.getZ() + pos2.getZ() + 1) * 0.5;

        poseStack.pushPose();
        poseStack.translate(cx, cy, cz);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.02f, -0.02f, 0.02f);
        float x = -mc.font.width(text) / 2.0f;
        mc.font.drawInBatch(
                text,
                x,
                0,
                0xFFFFFFFF,
                false,
                poseStack.last().pose(),
                bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0,
                0x00F000F0);
        poseStack.popPose();
    }

    private static void renderLineBox(PoseStack poseStack, VertexConsumer consumer, AABB box,
            float red, float green, float blue, float alpha) {
        int color = (toByte(alpha) << 24)
            | (toByte(red) << 16)
            | (toByte(green) << 8)
            | toByte(blue);
        ShapeRenderer.renderShape(poseStack, consumer, Shapes.create(box), 0.0, 0.0, 0.0, color, 1.0f);
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }
}
