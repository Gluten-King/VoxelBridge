package com.voxelbridge.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * A SubmitNodeCollector that routes entity/block-entity model submissions into a capturing
 * MultiBufferSource (e.g. the export CaptureBuffer), so vertex geometry can be captured.
 *
 * MC 26.1 entity rendering uses extractRenderState()+submit(state, pose, collector, camera)
 * instead of render(..., MultiBufferSource). This collector forwards the geometry-bearing
 * submit calls (submitModel/submitModelPart/submitCustomGeometry) into the buffer.
 */
public final class CapturingSubmitNodeCollector implements SubmitNodeCollector {

    private final MultiBufferSource buffer;

    public CapturingSubmitNodeCollector(MultiBufferSource buffer) {
        this.buffer = buffer;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 offset, int background, Component text, boolean forced, int packedLight, double distanceToCamera, net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow, Font.DisplayMode mode, int backgroundColor, int textColor, int packedLight, int packedOverlay) {
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
            VertexConsumer consumer = buffer.getBuffer(renderType);
            if (sprite != null) {
                consumer = sprite.wrap(consumer);
            }
            model.setupAnim(state);
            model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, tintedColor);
        }
    }

    @Override
    public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType,
                                int packedLight, int packedOverlay, TextureAtlasSprite sprite,
                                boolean sheeted, boolean hasFoil, int tintedColor,
                                ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int outlineColor) {
        if (modelPart != null && renderType != null) {
            VertexConsumer consumer = buffer.getBuffer(renderType);
            if (sprite != null) {
                consumer = sprite.wrap(consumer);
            }
            modelPart.render(poseStack, consumer, packedLight, packedOverlay, tintedColor);
        }
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tints, int packedLight, int packedOverlay, int outlineColor) {
        if (renderType == null || parts == null) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(renderType);
        QuadInstance instance = quadInstance(packedLight, packedOverlay);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                putQuads(part.getQuads(direction), poseStack.last(), consumer, instance, tints);
            }
            putQuads(part.getQuads(null), poseStack.last(), consumer, instance, tints);
        }
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
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
            buffer.getBuffer(material.itemRenderType())
                    .putBakedQuad(poseStack.last(), quad, instance);
        }
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (renderType != null && renderer != null) {
            VertexConsumer consumer = buffer.getBuffer(renderType);
            renderer.render(poseStack.last(), consumer);
        }
    }

    @Override
    public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
    }

    private static QuadInstance quadInstance(int packedLight, int packedOverlay) {
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(packedLight);
        instance.setOverlayCoords(packedOverlay);
        return instance;
    }

    private static void putQuads(List<BakedQuad> quads, PoseStack.Pose pose, VertexConsumer consumer,
                                 QuadInstance instance, int[] tints) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            instance.setColor(tintIndex >= 0 && tintIndex < tints.length ? tints[tintIndex] : -1);
            consumer.putBakedQuad(pose, quad, instance);
        }
    }
}
