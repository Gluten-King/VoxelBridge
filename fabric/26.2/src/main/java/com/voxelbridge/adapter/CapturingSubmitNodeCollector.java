package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxelbridge.platform.render.capture.BufferSource;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * SubmitNodeCollector that routes geometry-bearing submissions into a capturing BufferSource.
 * Updated for MC 26.2 SubmitNodeCollector / OrderedSubmitNodeCollector signatures.
 */
public final class CapturingSubmitNodeCollector implements SubmitNodeCollector {

    private final BufferSource buffer;
    private final Entity sourceEntity;

    public CapturingSubmitNodeCollector(BufferSource buffer) {
        this(buffer, null);
    }

    public CapturingSubmitNodeCollector(BufferSource buffer, Entity sourceEntity) {
        this.buffer = buffer;
        this.sourceEntity = sourceEntity;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 offset, int background, Component text, boolean forced,
                              int packedLight, CameraRenderState cameraRenderState) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow,
                           Font.DisplayMode mode, int backgroundColor, int textColor, int packedLight, int packedOverlay) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState state, org.joml.Quaternionf orientation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int packedLight, int packedOverlay, int tintedColor, TextureAtlasSprite sprite,
                                int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        if (model != null && renderType != null) {
            // The sprite parameter is part of the draw contract: model UVs are
            // sprite-local and must be expanded before atlas-sprite resolution.
            VertexConsumer consumer = spriteConsumer(renderType, sprite);
            model.setupAnim(state);
            model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, tintedColor);
            buffer.endBatch(renderType, sprite, BufferSource.UvSpace.ATLAS);
        }
    }

    // 26.2 default submitModelPart overloads funnel into this 9-arg form via interface defaults.
    // Override the 9-arg default so model parts are actually captured.
    @Override
    public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType,
                                int packedLight, int packedOverlay, TextureAtlasSprite sprite,
                                int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
                                int outlineColor) {
        if (modelPart != null && renderType != null) {
            VertexConsumer consumer = spriteConsumer(renderType, sprite);
            modelPart.render(poseStack, consumer, packedLight, packedOverlay, tintedColor);
            buffer.endBatch(renderType, sprite, BufferSource.UvSpace.ATLAS);
        }
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tints, int packedLight, int packedOverlay, int outlineColor) {
        if (renderType == null || parts == null) {
            return;
        }
        QuadInstance instance = quadInstance(packedLight, packedOverlay);
        int submittedQuads = 0;
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                List<BakedQuad> quads = part.getQuads(direction);
                submittedQuads += quads.size();
                putQuads(quads, poseStack.last(), buffer, renderType, instance, tints);
            }
            List<BakedQuad> unculled = part.getQuads(null);
            submittedQuads += unculled.size();
            putQuads(unculled, poseStack.last(), buffer, renderType, instance, tints);
        }
        if (submittedQuads == 0 && sourceEntity instanceof ItemFrame frame) {
            List<BakedQuad> fallbackQuads = itemFrameQuads(frame);
            submittedQuads = fallbackQuads.size();
            putQuads(fallbackQuads, poseStack.last(), buffer, renderType, instance, tints);
        }
        buffer.endBatch(renderType);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType,
                                   int color, float width, boolean expand) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int packedLight,
                           int packedOverlay, int outlineColor, int[] tints, List<BakedQuad> quads,
                           ItemStackRenderState.FoilType foilType) {
        if (quads == null) {
            return;
        }
        QuadInstance instance = quadInstance(packedLight, packedOverlay);
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            instance.setColor(material.isTinted() && tintIndex >= 0 && tintIndex < tints.length
                    ? tints[tintIndex]
                    : -1);
            RenderType renderType = material.itemRenderType();
            TextureAtlasSprite sprite = material.sprite();
            buffer.getBuffer(renderType, sprite, BufferSource.UvSpace.ATLAS)
                    .putBakedQuad(poseStack.last(), quad, instance);
            buffer.endBatch(renderType, sprite, BufferSource.UvSpace.ATLAS);
        }
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (renderType != null && renderer != null) {
            VertexConsumer consumer = buffer.getBuffer(renderType);
            renderer.render(poseStack.last(), consumer);
            buffer.endBatch(renderType);
        }
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState state) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState cameraRenderState,
                                      boolean translucent) {
        // Capture path does not need gizmo geometry.
    }

    private static QuadInstance quadInstance(int packedLight, int packedOverlay) {
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(packedLight);
        instance.setOverlayCoords(packedOverlay);
        return instance;
    }

    private VertexConsumer spriteConsumer(RenderType renderType, TextureAtlasSprite sprite) {
        VertexConsumer consumer = buffer.getBuffer(
            renderType, sprite, sprite != null ? BufferSource.UvSpace.ATLAS : BufferSource.UvSpace.UNKNOWN);
        return sprite != null ? new SpriteCoordinateExpander(consumer, sprite) : consumer;
    }

    private static void putQuads(List<BakedQuad> quads, PoseStack.Pose pose, BufferSource buffer,
                                 RenderType fallbackRenderType, QuadInstance instance, int[] tints) {
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            int tintIndex = material.tintIndex();
            instance.setColor(tintIndex >= 0 && tintIndex < tints.length ? tints[tintIndex] : -1);
            RenderType renderType = material.itemRenderType() != null
                ? material.itemRenderType() : fallbackRenderType;
            TextureAtlasSprite sprite = material.sprite();
            VertexConsumer consumer = buffer.getBuffer(
                renderType, sprite, sprite != null ? BufferSource.UvSpace.ATLAS : BufferSource.UvSpace.UNKNOWN);
            consumer.putBakedQuad(pose, quad, instance);
            buffer.endBatch(
                renderType, sprite, sprite != null ? BufferSource.UvSpace.ATLAS : BufferSource.UvSpace.UNKNOWN);
        }
    }

    private static List<BakedQuad> itemFrameQuads(ItemFrame frame) {
        boolean glow = frame instanceof net.minecraft.world.entity.decoration.GlowItemFrame;
        boolean map = frame.getFramedMapId(frame.getItem()) != null;
        net.minecraft.world.level.block.state.BlockState state =
            net.minecraft.client.resources.model.BlockStateDefinitions.getItemFrameFakeState(glow, map);
        Object model = Adapters.getRender().getBlockModel(state);
        return Adapters.getRender().getQuads(
            model,
            state,
            frame.blockPosition(),
            frame.level(),
            42L);
    }
}
