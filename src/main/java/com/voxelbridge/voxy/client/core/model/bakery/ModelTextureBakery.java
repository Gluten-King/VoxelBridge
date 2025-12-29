package com.voxelbridge.voxy.client.core.model.bakery;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL14;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL45.glTextureBarrier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.lang.reflect.Method;

public class ModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final GlViewCapture capture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();

    private final int width;
    private final int height;
    public ModelTextureBakery(int width, int height) {
        this.capture = new GlViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    public static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = isCutout(layer) || isTranslucent(layer) || isTripwire(layer);

        boolean isMipped = isSolid(layer) || isTranslucent(layer) || isTripwire(layer);

        int meta = hasDiscard?1:0;
        meta |= true?2:0;
        return meta;
    }

    private void bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = getMetaFromLayer(layer);

        RandomSource rand = RandomSource.create(42L);
        long seed = 42L;
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            rand.setSeed(seed);
            var quads = model.getQuads(state, direction, rand, ModelData.EMPTY, layer);
            for (var quad : quads) {
                this.vc.quad(quad, meta | (quad.isTinted() ? 4 : 0));
            }
        }
    }


    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        {
            //TODO: somehow set the tint flag per quad or something?
            int metadata = getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);//Set the meta while baking
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        this.vc.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.capture.free();
        this.vc.free();
    }

    private static boolean isSolid(RenderType layer) {
        return layer == RenderType.solid();
    }

    private static boolean isCutout(RenderType layer) {
        return layer == RenderType.cutout() || layer == RenderType.cutoutMipped();
    }

    private static boolean isTranslucent(RenderType layer) {
        return layer == RenderType.translucent();
    }

    private static boolean isTripwire(RenderType layer) {
        return layer == RenderType.tripwire();
    }


    public int renderToStream(BlockState state, int streamBuffer, int streamOffset) {
        this.capture.clear();
        boolean isBlock = true;
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderType.solid();
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        //Setup GL state
        int[] viewdat = new int[4];
        int blockTextureId;

        {
            glEnable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            //glEnable(GL_CULL_FACE); // DISABLED FOR TESTING
            glDisable(GL_CULL_FACE); // TESTING: Disable face culling
            if (isTranslucent(layer)) {
                glEnablei(GL_BLEND, 0);
                glDisablei(GL_BLEND, 1);
                glBlendFuncSeparatei(0, GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);//FUCK YOU INTEL (screams), for _some reason_ discard or something... JUST DOESNT WORK??
                //glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ONE, GL_ONE);
            }

            glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilMask(0xFF);

            glGetIntegerv(GL_VIEWPORT, viewdat);//TODO: faster way todo this, or just use main framebuffer resolution

            //Bind the capture framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);

            // DEBUG: Check framebuffer status
            int fbStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (fbStatus != GL_FRAMEBUFFER_COMPLETE) {
                VoxelBridgeLogger.error(LogModule.LOD_BAKE, "[GPU] Framebuffer incomplete! Status: " + fbStatus);
                throw new IllegalStateException("Framebuffer incomplete: " + fbStatus);
            }

            blockTextureId = resolveBlockAtlasTextureId();

            // DEBUG: Log texture ID and framebuffer status
            if (ExportRuntimeConfig.isLodBakeDebugEnabled()) {
                VoxelBridgeLogger.info(LogModule.LOD_BAKE, String.format(
                    "[GPU] blockTextureId=%d, fbId=%d, width=%d, height=%d",
                    blockTextureId, this.capture.framebuffer.id, this.width, this.height
                ));
            }
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            if (ExportRuntimeConfig.isLodBakeDebugEnabled() && this.vc.isEmpty()) {
                VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[Bake] Empty bake for " + state);
            }
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (!this.vc.isEmpty()) {//only render if there... is shit to render

                //Setup for continual emission
                if (ExportRuntimeConfig.isLodBakeDebugEnabled() && blockTextureId == 0) {
                    VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[Bake] Block atlas texture id is 0 for " + state);
                }
                BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);//note: this.vc.buffer.address NOT this.vc.ptr

                // DEBUG: Log render setup
                if (ExportRuntimeConfig.isLodBakeDebugEnabled()) {
                    VoxelBridgeLogger.info(LogModule.LOD_BAKE, String.format(
                        "[GPU] Rendering %d quads with texId=%d, layer=%s",
                        this.vc.quadCount(), blockTextureId, layer
                    ));
                }

                var mat = new Matrix4f();
                for (int i = 0; i < VIEWS.length; i++) {
                    // DISABLED FOR TESTING - Face culling is off
                    //if (i==1||i==2||i==4) {
                    //    glCullFace(GL_FRONT);
                    //} else {
                    //    glCullFace(GL_BACK);
                    //}

                    glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                    //The projection matrix
                    mat.set(2, 0, 0, 0,
                            0, 2, 0, 0,
                            0, 0, -1f, 0,
                            -1, -1, 0, 1)
                            .mul(VIEWS[i]);

                    BudgetBufferRenderer.render(mat);

                    // DEBUG: Check OpenGL errors after render
                    int error = glGetError();
                    if (error != GL_NO_ERROR && ExportRuntimeConfig.isLodBakeDebugEnabled()) {
                        VoxelBridgeLogger.error(LogModule.LOD_BAKE, String.format(
                            "[GPU] OpenGL error after rendering face %d: 0x%X", i, error
                        ));
                    }
                }
            }
            glBindVertexArray(0);
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                // DISABLED FOR TESTING - Face culling is off
                //if (i==1||i==2||i==4) {
                //    glCullFace(GL_FRONT);
                //} else {
                //    glCullFace(GL_BACK);
                //}

            this.vc.reset();
            this.bakeFluidState(state, layer, i);
            if (this.vc.isEmpty()) continue;
            isAnyShaded |= this.vc.anyShaded;
            isAnyDarkend |= this.vc.anyDarkendTex;
            if (ExportRuntimeConfig.isLodBakeDebugEnabled() && blockTextureId == 0) {
                VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[Bake] Block atlas texture id is 0 for " + state);
            }
            BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                mat.set(2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -1f, 0,
                        -1, -1, 0, 1)
                        .mul(VIEWS[i]);

                BudgetBufferRenderer.render(mat);
            }
            glBindVertexArray(0);
        }

        //Render block model entity data if it exists
        /*
        if (bbem != null) {
            //Rerender everything again ;-; but is ok (is not)

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                if (i==1||i==2||i==4) {
                    glCullFace(GL_FRONT);
                } else {
                    glCullFace(GL_BACK);
                }

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                mat.set(2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -1f, 0,
                        -1, -1, 0, 1)
                        .mul(VIEWS[i]);

                bbem.render(mat, blockTextureId);
            }
            glBindVertexArray(0);

            bbem.release();
        }*/



        //"Restore" gl state
        glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);

        //Finish and download
        glTextureBarrier();
        this.capture.emitToStream(streamBuffer, streamOffset);

        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
        glClearDepth(1);
        glClear(GL_DEPTH_BUFFER_BIT);
        if (isTranslucent(layer)) {
            //reset the blend func
            GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0);
    }

    private static int resolveBlockAtlasTextureId() {
        var tex = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        int texId = tex.getId();

        // DEBUG: Log all attempts to bake.log
        VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] tex.getId() = " + texId);

        if (texId != 0) {
            VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] Returning texId from tex.getId(): " + texId);
            return texId;
        }

        VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] tex.getId() returned 0, trying reflection...");

        try {
            Method getTexture = tex.getClass().getMethod("getTexture");
            Object gpuTex = getTexture.invoke(tex);
            VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] getTexture() returned: " + gpuTex);

            if (gpuTex == null) {
                VoxelBridgeLogger.warn(LogModule.LOD_BAKE, "[TextureID] getTexture() returned null!");
                return texId;
            }

            try {
                Method getId = gpuTex.getClass().getMethod("getId");
                Object val = getId.invoke(gpuTex);
                VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] gpuTex.getId() = " + val);
                if (val instanceof Integer id) {
                    VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] Returning from gpuTex.getId(): " + id);
                    return id;
                }
            } catch (NoSuchMethodException e) {
                VoxelBridgeLogger.warn(LogModule.LOD_BAKE, "[TextureID] getId() method not found");
            }

            try {
                Method glId = gpuTex.getClass().getMethod("glId");
                Object val = glId.invoke(gpuTex);
                VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] gpuTex.glId() = " + val);
                if (val instanceof Integer id) {
                    VoxelBridgeLogger.info(LogModule.LOD_BAKE, "[TextureID] Returning from gpuTex.glId(): " + id);
                    return id;
                }
            } catch (NoSuchMethodException e) {
                VoxelBridgeLogger.warn(LogModule.LOD_BAKE, "[TextureID] glId() method not found");
            }
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.LOD_BAKE, "[TextureID] Exception in resolveBlockAtlasTextureId: " + e.getMessage());
            VoxelBridgeLogger.error(LogModule.LOD_BAKE, "[TextureID] Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }

        VoxelBridgeLogger.warn(LogModule.LOD_BAKE, "[TextureID] All methods failed, returning default texId = " + texId);
        return texId;
    }



    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}

