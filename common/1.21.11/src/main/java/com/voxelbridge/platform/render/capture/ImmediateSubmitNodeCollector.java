package com.voxelbridge.platform.render.capture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Executes 1.21.11 submit nodes immediately against VoxelBridge's capture buffer.
 */
public final class ImmediateSubmitNodeCollector implements SubmitNodeCollector {
    private final MultiBufferSource buffers;

    public ImmediateSubmitNodeCollector(MultiBufferSource buffers) {
        this.buffers = buffers;
    }

    public static CameraRenderState cameraState(Vec3 fallbackPosition) {
        CameraRenderState state = new CameraRenderState();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 position = camera != null && camera.isInitialized() ? camera.position() : fallbackPosition;
        if (position == null) {
            position = Vec3.ZERO;
        }
        state.pos = position;
        state.entityPos = position;
        state.blockPos = net.minecraft.core.BlockPos.containing(position);
        state.orientation = camera != null && camera.isInitialized()
            ? new Quaternionf(camera.rotation())
            : new Quaternionf();
        state.initialized = true;
        return state;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        // Shadows are environment effects and are intentionally excluded from exported entity geometry.
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 offset, int verticalOffset, Component text,
            boolean discrete, int lightCoords, double distance, CameraRenderState cameraState) {
        // Name tags are HUD-like decorations rather than entity geometry.
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text,
            boolean shadow, Font.DisplayMode displayMode, int color, int backgroundColor,
            int lightCoords, int outlineColor) {
        Minecraft.getInstance().font.drawInBatch(
            text, x, y, color, shadow, poseStack.last().pose(), buffers,
            displayMode, backgroundColor, lightCoords
        );
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf cameraOrientation) {
        // Fire overlays are transient environment effects.
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState state) {
        // Kept out of the exported entity mesh; leash endpoints can reference entities outside the selection.
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack,
            RenderType renderType, int lightCoords, int overlayCoords, int tintedColor,
            TextureAtlasSprite sprite, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        VertexConsumer consumer = buffers.getBuffer(renderType);
        if (sprite != null) {
            consumer = sprite.wrap(consumer);
        }
        model.setupAnim(state);
        model.renderToBuffer(poseStack, consumer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public void submitModelPart(ModelPart part, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, TextureAtlasSprite sprite, boolean usePrimaryColor,
            boolean useSecondaryColor, int tintedColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int outlineColor) {
        VertexConsumer consumer = buffers.getBuffer(renderType);
        if (sprite != null) {
            consumer = sprite.wrap(consumer);
        }
        part.render(poseStack, consumer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public void submitBlock(PoseStack poseStack, BlockState state, int lightCoords,
            int overlayCoords, int outlineColor) {
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
            state, poseStack, buffers, lightCoords, overlayCoords
        );
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingState) {
        BlockState state = movingState.blockState;
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var model = dispatcher.getBlockModel(state);
        var parts = model.collectParts(RandomSource.create(state.getSeed(movingState.randomSeedPos)));
        dispatcher.getModelRenderer().tesselateBlock(
            movingState,
            parts,
            state,
            movingState.blockPos,
            poseStack,
            buffers.getBuffer(ItemBlockRenderTypes.getMovingBlockRenderType(state)),
            false,
            OverlayTexture.NO_OVERLAY
        );
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, BlockStateModel model,
            float red, float green, float blue, int lightCoords, int overlayCoords,
            int outlineColor) {
        ModelBlockRenderer.renderModel(
            poseStack.last(), buffers.getBuffer(renderType), model,
            red, green, blue, lightCoords, overlayCoords
        );
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext,
            int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
            List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
            RenderType renderType, ItemStackRenderState.FoilType foilType) {
        ItemRenderer.renderItem(
            displayContext, poseStack, buffers, lightCoords, overlayCoords,
            tintLayers, quads, renderType, foilType
        );
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
            CustomGeometryRenderer renderer) {
        renderer.render(poseStack.last(), buffers.getBuffer(renderType));
    }

    @Override
    public void submitParticleGroup(ParticleGroupRenderer renderer) {
        // Particles are intentionally excluded from exported entity geometry.
    }
}
