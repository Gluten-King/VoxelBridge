package com.voxelbridge.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import com.voxelbridge.voxy.client.core.gl.GlDebug;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL14;

import java.lang.reflect.Method;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL45.glTextureBarrier;

public class ModelTextureBakery {
    private static final Matrix4f[] VIEWS = new Matrix4f[6];
    public record UvStats(float minU, float maxU, float minV, float maxV, int count, boolean invalid, String debugLog,
                          float[] points, int pointCount) {}

    public enum BakeMode {
        ALL, BASE_ONLY, OVERLAY_ONLY
    }

    private final GlViewCapture capture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();

    private final int width;
    private final int height;
    private boolean includeOverlay = true;
    private BakeMode bakeMode = BakeMode.ALL;
    private float lastMinU = Float.NaN;
    private float lastMaxU = Float.NaN;
    private float lastMinV = Float.NaN;
    private float lastMaxV = Float.NaN;
    private int lastUvCount = 0;
    private boolean lastUvInvalid = false;
    private String lastDebugLog = "";
    private float[] lastUvPoints = new float[0];
    private int lastUvPointCount = 0;
    private boolean captureUvPoints = false;

    public ModelTextureBakery(int width, int height) {
        this.capture = new GlViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    public void setIncludeOverlay(boolean includeOverlay) {
        this.includeOverlay = includeOverlay;
    }

    public void setBakeMode(BakeMode mode) {
        this.bakeMode = mode;
    }

    public static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = isCutout(layer) || isTranslucent(layer) || isTripwire(layer);
        boolean isMipped = isSolid(layer) || isTranslucent(layer) || isTripwire(layer);
        int meta = hasDiscard ? 1 : 0;
        meta |= isMipped ? 2 : 0;
        return meta;
    }

    public UvStats getLastUvStats() {
        return new UvStats(this.lastMinU, this.lastMaxU, this.lastMinV, this.lastMaxV, this.lastUvCount,
            this.lastUvInvalid, this.lastDebugLog, this.lastUvPoints, this.lastUvPointCount);
    }

    public void setCaptureUvPoints(boolean capture) {
        this.captureUvPoints = capture;
        this.vc.setCaptureUvPoints(capture);
    }

    public boolean hasOverlay(BlockState state) {
        if (!includeOverlay) return false;
        var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
        RandomSource rand = RandomSource.create(42L);
        long seed = 42L;
        // Check standard quads
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            rand.setSeed(seed);
            // Check base layer
            var quads = model.getQuads(state, direction, rand, ModelData.EMPTY, ItemBlockRenderTypes.getChunkRenderType(state));
            for (var quad : quads) {
                if (isOverlaySprite(quad.getSprite())) return true;
            }
            rand.setSeed(seed);
            // Check fallback/overlay layer
            quads = model.getQuads(state, direction, rand, ModelData.EMPTY, null);
            for (var quad : quads) {
                if (isOverlaySprite(quad.getSprite())) return true;
            }
        }
        return false;
    }

    private BlockAndTintGetter makeTintGetter(BlockState state) {
        return new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) { return 0; }
            @Override
            public LevelLightEngine getLightEngine() { return null; }
            @Override
            public int getBrightness(LightLayer type, BlockPos pos) { return 0; }
            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                // Return Plains color as default for baked tint
                var biome = Minecraft.getInstance().level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)
                    .value();
                return colorResolver.getColor(biome, pos.getX(), pos.getZ());
            }
            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override
            public BlockState getBlockState(BlockPos pos) { return state; }
            @Override
            public FluidState getFluidState(BlockPos pos) { return state.getFluidState(); }
            @Override
            public int getHeight() { return 0; }
            @Override
            public int getMinBuildHeight() { return 0; }
        };
    }

    private void bakeBlockModel(BlockState state, RenderType layer, boolean bakeTint, BlockColors blockColors) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int metaBase = getMetaFromLayer(layer);
        RandomSource rand = RandomSource.create(42L);
        long seed = 42L;
        
        BlockAndTintGetter tintGetter = bakeTint ? makeTintGetter(state) : null;

        // Pass 1: Base Layer
        if (bakeMode == BakeMode.ALL || bakeMode == BakeMode.BASE_ONLY) {
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                rand.setSeed(seed);
                var quads = model.getQuads(state, direction, rand, ModelData.EMPTY, layer);
                for (var quad : quads) {
                    if (!includeOverlay && isOverlaySprite(quad.getSprite())) {
                        continue;
                    }
                    // If separating overlays, ensure we don't accidentally render overlay quads in base pass if they leaked here
                    if (bakeMode == BakeMode.BASE_ONLY && isOverlaySprite(quad.getSprite())) {
                        continue;
                    }

                    // Base layer: Always use White (no baked tint).
                    this.vc.setColor(0xFFFFFFFF);
                    this.vc.quad(quad, metaBase);
                }
            }
        }

        // Pass 2: Overlay
        boolean doOverlay = (includeOverlay && bakeMode == BakeMode.ALL) || bakeMode == BakeMode.OVERLAY_ONLY;
        if (doOverlay) {
            rand.setSeed(seed);
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
                rand.setSeed(seed);
                var quads = model.getQuads(state, direction, rand, ModelData.EMPTY, null);
                for (var quad : quads) {
                    if (!isOverlaySprite(quad.getSprite())) {
                        continue;
                    }
                    
                    if (bakeTint) {
                        int color = 0xFFFFFFFF;
                        if (quad.isTinted()) {
                            color = blockColors.getColor(state, tintGetter, BlockPos.ZERO, quad.getTintIndex());
                            color |= 0xFF000000;
                        }
                        this.vc.setColor(color);
                    } else {
                        this.vc.setColor(0xFFFFFFFF);
                    }
                    this.vc.quad(quad, metaBase);
                }
            }
        }
    }

    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        int metadata = getMetaFromLayer(layer);
        this.vc.setDefaultMeta(metadata);
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
        this.vc.setDefaultMeta(0);
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
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

    private static boolean isOverlaySprite(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        if (sprite == null) {
            return false;
        }
        return sprite.contents().name().toString().contains("overlay");
    }

    public int renderToStream(BlockState state, int streamBuffer, int streamOffset) {
        GlDebug.pushGroup("VoxelBridge GPU Bake");
        try {
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

            int[] viewdat = new int[4];
            int blockTextureId;

            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            boolean prevCull = glIsEnabled(GL_CULL_FACE);
            glEnable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDisable(GL_CULL_FACE);
            if (isTranslucent(layer)) {
                glEnablei(GL_BLEND, 0);
                glDisablei(GL_BLEND, 1);
                glBlendFuncSeparatei(0, GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);
            }

            glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilMask(0xFF);

            glGetIntegerv(GL_VIEWPORT, viewdat);
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);

            int fbStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (fbStatus != GL_FRAMEBUFFER_COMPLETE) {
                VoxelBridgeLogger.error(LogModule.LOD, "[GPU] Framebuffer incomplete: " + fbStatus);
                throw new IllegalStateException("Framebuffer incomplete: " + fbStatus);
            }

            blockTextureId = resolveBlockAtlasTextureId();
            if (blockTextureId == 0) {
                VoxelBridgeLogger.warn(LogModule.BAKE, "[Bake] Block atlas texture id is 0; UV sampling will be invalid.");
            }

            // Determine if we should bake tint (color) into the texture.
            // If we are splitting passes (BASE/OVERLAY only), we DO NOT bake tint, 
            // because we want the runtime mesher to apply the correct biome color.
            // Exception: Fluids always bake tint.
            boolean isSplitPass = bakeMode != BakeMode.ALL;
            boolean bakeTint = !isSplitPass && ((isBlock && hasOverlay(state)) || !isBlock);
            
            this.vc.setApplyTint(bakeTint);
            BlockColors blockColors = Minecraft.getInstance().getBlockColors();

            boolean isAnyShaded = false;
            boolean isAnyDarkend = false;
            float minU = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            int uvCount = 0;
            boolean uvInvalid = false;
            GlDebug.pushGroup("Bake Draw");
            try {
                if (isBlock) {
                    this.vc.reset();
                    this.bakeBlockModel(state, layer, bakeTint, blockColors);
                    isAnyShaded |= this.vc.anyShaded;
                    isAnyDarkend |= this.vc.anyDarkendTex;
                    if (!this.vc.isEmpty()) {
                        minU = Math.min(minU, this.vc.getMinU());
                        maxU = Math.max(maxU, this.vc.getMaxU());
                        minV = Math.min(minV, this.vc.getMinV());
                        maxV = Math.max(maxV, this.vc.getMaxV());
                        uvCount += this.vc.getUvCount();
                        uvInvalid |= this.vc.hasInvalidUv();
                        BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);
                        var mat = new Matrix4f();
                        for (int i = 0; i < VIEWS.length; i++) {
                            if (i == 1 || i == 2 || i == 4) {
                                glCullFace(GL_FRONT);
                            } else {
                                glCullFace(GL_BACK);
                            }

                            glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                            mat.set(2, 0, 0, 0,
                                    0, 2, 0, 0,
                                    0, 0, -1f, 0,
                                    -1, -1, 0, 1)
                                    .mul(VIEWS[i]);

                            BudgetBufferRenderer.render(mat);
                        }
                        logBakeSummary("block", state, layer, blockTextureId, this.vc, this.vc.quadCount(), isAnyShaded, isAnyDarkend);
                    }
                    glBindVertexArray(0);
                } else {
                    if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();

                    var mat = new Matrix4f();
                    for (int i = 0; i < VIEWS.length; i++) {
                        if (i == 1 || i == 2 || i == 4) {
                            glCullFace(GL_FRONT);
                        } else {
                            glCullFace(GL_BACK);
                        }

                        this.vc.reset();
                        this.bakeFluidState(state, layer, i);
                        if (this.vc.isEmpty()) continue;
                        minU = Math.min(minU, this.vc.getMinU());
                        maxU = Math.max(maxU, this.vc.getMaxU());
                        minV = Math.min(minV, this.vc.getMinV());
                        maxV = Math.max(maxV, this.vc.getMaxV());
                        uvCount += this.vc.getUvCount();
                        uvInvalid |= this.vc.hasInvalidUv();
                        isAnyShaded |= this.vc.anyShaded;
                        isAnyDarkend |= this.vc.anyDarkendTex;
                        BudgetBufferRenderer.setup(this.vc.getAddress(), this.vc.quadCount(), blockTextureId);

                        glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                        mat.set(2, 0, 0, 0,
                                0, 2, 0, 0,
                                0, 0, -1f, 0,
                                -1, -1, 0, 1)
                                .mul(VIEWS[i]);

                        BudgetBufferRenderer.render(mat);
                        logBakeSummary("fluid:" + Direction.from3DDataValue(i).getSerializedName(), state, layer, blockTextureId, this.vc, this.vc.quadCount(), this.vc.anyShaded, this.vc.anyDarkendTex);
                    }
                    glBindVertexArray(0);
                }
            } finally {
                GlDebug.popGroup();
            }

            glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_BLEND);
            if (prevCull) {
                glEnable(GL_CULL_FACE);
            } else {
                glDisable(GL_CULL_FACE);
            }
            glDepthFunc(prevDepthFunc);

            glTextureBarrier();
            GlDebug.pushGroup("Bake CopyOut");
            try {
                this.capture.emitToStream(streamBuffer, streamOffset);
            } finally {
                GlDebug.popGroup();
            }

            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
            glClearDepth(1);
            glClear(GL_DEPTH_BUFFER_BIT);
            if (isTranslucent(layer)) {
                GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            }

            if (uvCount > 0) {
                this.lastMinU = minU;
                this.lastMaxU = maxU;
                this.lastMinV = minV;
                this.lastMaxV = maxV;
                this.lastUvCount = uvCount;
                this.lastUvInvalid = uvInvalid;
                this.lastDebugLog = this.vc.getDebugLog();
                if (this.captureUvPoints && this.vc.getUvPointCount() > 0) {
                    this.lastUvPoints = this.vc.copyUvPoints();
                    this.lastUvPointCount = this.vc.getUvPointCount();
                } else {
                    this.lastUvPoints = new float[0];
                    this.lastUvPointCount = 0;
                }
            } else {
                this.lastMinU = Float.NaN;
                this.lastMaxU = Float.NaN;
                this.lastMinV = Float.NaN;
                this.lastMaxV = Float.NaN;
                this.lastUvCount = 0;
                this.lastUvInvalid = uvInvalid;
                this.lastDebugLog = "";
                this.lastUvPoints = new float[0];
                this.lastUvPointCount = 0;
            }

            return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (bakeTint ? 4 : 0);
        } finally {
            GlDebug.popGroup();
        }
    }
    private static int resolveBlockAtlasTextureId() {
        Minecraft mc = Minecraft.getInstance();
        int texId = resolveTextureId(mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS));
        if (texId != 0) {
            return texId;
        }
        return resolveTextureId(mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS));
    }

    private static int resolveTextureId(Object texture) {
        if (texture == null) {
            return 0;
        }
        if (texture instanceof AbstractTexture abstractTexture) {
            int id = abstractTexture.getId();
            if (id != 0) {
                return id;
            }
        }
        Object gpuTexture = invokeOptional(texture, "getTexture");
        int id = resolveGpuTextureId(gpuTexture);
        if (id != 0) {
            return id;
        }
        return resolveGpuTextureId(texture);
    }

    private static int resolveGpuTextureId(Object texture) {
        if (texture == null) {
            return 0;
        }
        Object val = invokeOptional(texture, "getId");
        if (val instanceof Integer id && id != 0) {
            return id;
        }
        val = invokeOptional(texture, "glId");
        if (val instanceof Integer id && id != 0) {
            return id;
        }
        return 0;
    }

    private static Object invokeOptional(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void logBakeSummary(String label, BlockState state, RenderType layer, int texId, ReuseVertexConsumer vc, int quadCount, boolean anyShaded, boolean anyDarkend) {
        if (!VoxelBridgeLogger.isDebugEnabled(LogModule.BAKE)) {
            return;
        }
        int uvCount = vc.getUvCount();
        String uvInfo;
        if (uvCount == 0) {
            uvInfo = "uv=empty";
        } else {
            uvInfo = String.format("uv=[%.6f..%.6f, %.6f..%.6f] uvCount=%d",
                vc.getMinU(), vc.getMaxU(), vc.getMinV(), vc.getMaxV(), uvCount);
        }
        VoxelBridgeLogger.debug(LogModule.BAKE, String.format(
            "[Bake][%s] state=%s layer=%s quads=%d shaded=%s darkened=%s atlasTex=%d %s",
            label, state, layer, quadCount, anyShaded, anyDarkend, texId, uvInfo));

        if (vc.hasInvalidUv()) {
            VoxelBridgeLogger.warn(LogModule.BAKE, "[Bake][UV] Invalid UV encountered (NaN/Inf).");
        } else if (uvCount > 0) {
            float minU = vc.getMinU();
            float maxU = vc.getMaxU();
            float minV = vc.getMinV();
            float maxV = vc.getMaxV();
            if (minU < -0.01f || maxU > 1.01f || minV < -0.01f || maxV > 1.01f) {
                VoxelBridgeLogger.warn(LogModule.BAKE, String.format(
                    "[Bake][UV] UV out of [0,1] range: [%.6f..%.6f, %.6f..%.6f]",
                    minU, maxU, minV, maxV));
            }
        }
    }

    static {
        addView(0, -90, 0, 0, 0);
        addView(1, 90, 0, 0, 0b100);
        addView(2, 0, 180, 0, 0b001);
        addView(3, 0, 0, 0, 0);
        addView(4, 0, 90, 270, 0b100);
        addView(5, 0, 270, 270, 0);
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        VIEWS[i] = new Matrix4f(stack.last().pose());
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
