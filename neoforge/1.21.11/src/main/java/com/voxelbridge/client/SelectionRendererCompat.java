package com.voxelbridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.ExportProgressTracker.ChunkState;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Map;

public final class SelectionRendererCompat {
    private SelectionRendererCompat() {
    }

    public static void onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        BlockPos pos1 = ExportControl.getPos1();
        BlockPos pos2 = ExportControl.getPos2();
        if (pos1 == null && pos2 == null) return;

        Minecraft minecraft = ClientAccessHolder.get().getMinecraft();
        if (event.getLevelRenderState().cameraRenderState == null) return;
        Vec3 cameraPos = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderTypes.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        if (pos1 != null) renderBox(poseStack, consumer, pos1, 1.0f, 0.2f, 0.2f, 0.6f);
        if (pos2 != null) renderBox(poseStack, consumer, pos2, 0.2f, 0.2f, 1.0f, 0.6f);
        if (pos1 != null && pos2 != null) {
            renderSelectionBox(poseStack, consumer, pos1, pos2, 0.0f, 1.0f, 1.0f, 0.5f);
            renderChunkStatus(poseStack, consumer, pos1, pos2);
            renderProgressLabel(poseStack, minecraft, pos1, pos2, buffers);
        }

        poseStack.popPose();
        buffers.endBatch(RenderTypes.lines());
    }

    private static void renderBox(PoseStack poseStack, VertexConsumer consumer,
                                  BlockPos pos, float red, float green, float blue, float alpha) {
        renderLineBox(poseStack, consumer, new AABB(pos).inflate(0.002), red, green, blue, alpha);
    }

    private static void renderSelectionBox(PoseStack poseStack, VertexConsumer consumer,
                                           BlockPos pos1, BlockPos pos2,
                                           float red, float green, float blue, float alpha) {
        AABB box = new AABB(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()) + 1,
                Math.max(pos1.getY(), pos2.getY()) + 1,
                Math.max(pos1.getZ(), pos2.getZ()) + 1);
        renderLineBox(poseStack, consumer, box, red, green, blue, alpha);
    }

    private static void renderChunkStatus(PoseStack poseStack, VertexConsumer consumer,
                                          BlockPos pos1, BlockPos pos2) {
        var states = ExportProgressTracker.snapshot();
        if (states.isEmpty()) return;

        int selectionMinX = Math.min(pos1.getX(), pos2.getX());
        int selectionMinY = Math.min(pos1.getY(), pos2.getY());
        int selectionMinZ = Math.min(pos1.getZ(), pos2.getZ());
        int selectionMaxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int selectionMaxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int selectionMaxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        for (Map.Entry<Long, ChunkState> entry : states.entrySet()) {
            int chunkMinX = ChunkPos.getX(entry.getKey()) << 4;
            int chunkMinZ = ChunkPos.getZ(entry.getKey()) << 4;
            int boxMinX = Math.max(chunkMinX, selectionMinX);
            int boxMinZ = Math.max(chunkMinZ, selectionMinZ);
            int boxMaxX = Math.min(chunkMinX + 16, selectionMaxX);
            int boxMaxZ = Math.min(chunkMinZ + 16, selectionMaxZ);
            if (boxMinX >= boxMaxX || selectionMinY >= selectionMaxY || boxMinZ >= boxMaxZ) continue;

            float red;
            float green;
            float blue;
            if (entry.getValue() == ChunkState.DONE) {
                red = 0.1f; green = 1.0f; blue = 0.1f;
            } else if (entry.getValue() == ChunkState.RUNNING) {
                red = 1.0f; green = 0.8f; blue = 0.1f;
            } else {
                red = 1.0f; green = 0.2f; blue = 0.2f;
            }
            renderLineBox(poseStack, consumer,
                    new AABB(boxMinX, selectionMinY, boxMinZ, boxMaxX, selectionMaxY, boxMaxZ),
                    red, green, blue, 0.35f);
        }
    }

    private static void renderProgressLabel(PoseStack poseStack, Minecraft minecraft,
                                            BlockPos pos1, BlockPos pos2,
                                            MultiBufferSource buffers) {
        ExportProgressTracker.Progress progress = ExportProgressTracker.progress();
        if (progress.total() <= 0) return;
        String text = String.format("Export: %d/%d (%.1f%%)",
                progress.done(), progress.total(), progress.percent());

        poseStack.pushPose();
        poseStack.translate(
                (pos1.getX() + pos2.getX() + 1) * 0.5,
                (pos1.getY() + pos2.getY() + 1) * 0.5 + 1.5,
                (pos1.getZ() + pos2.getZ() + 1) * 0.5);
        poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.02f, -0.02f, 0.02f);
        minecraft.font.drawInBatch(text, -minecraft.font.width(text) / 2.0f, 0,
                0xFFFFFFFF, false, poseStack.last().pose(), buffers,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 0x00F000F0);
        poseStack.popPose();
    }

    private static void renderLineBox(PoseStack poseStack, VertexConsumer consumer, AABB box,
                                      float red, float green, float blue, float alpha) {
        int color = (toByte(alpha) << 24) | (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
        ShapeRenderer.renderShape(poseStack, consumer, Shapes.create(box),
                0.0, 0.0, 0.0, color, ExportRuntimeConfig.getSelectionLineWidth());
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }
}
