package com.voxelbridge.voxy.mesh;

import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.common.world.WorldSection;
import com.voxelbridge.voxy.common.world.other.Mapper;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.awt.image.BufferedImage;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class VoxelMesher {
    public interface LodTextureProvider {
        boolean hasTextures(int blockId);
        String getSpriteKey(int blockId, Direction dir);
    }

    public interface LodFaceProvider extends LodTextureProvider {
        LodFaceMeta getFaceMeta(int blockId, Direction dir);
    }

    public interface LodOverlayProvider extends LodFaceProvider {
        String getOverlaySpriteKey(int blockId, Direction dir);
    }

    public static final class LodFaceMeta {
        public final int minX;
        public final int maxX;
        public final int minY;
        public final int maxY;
        public final float depth;
        public final boolean empty;
        public final boolean hasBakedTint;

        public LodFaceMeta(int minX, int maxX, int minY, int maxY, float depth, boolean empty) {
            this(minX, maxX, minY, maxY, depth, empty, false);
        }

        public LodFaceMeta(int minX, int maxX, int minY, int maxY, float depth, boolean empty, boolean hasBakedTint) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.depth = depth;
            this.empty = empty;
            this.hasBakedTint = hasBakedTint;
        }
    }
    private final WorldEngine worldEngine;
    private final Mapper mapper;
    private final SceneSink sink;
    private final LodTextureProvider lodTextureProvider;
    private final com.voxelbridge.export.ExportContext exportContext;
    private final RandomSource random = RandomSource.create();
    private final BlockColors blockColors;
    private final net.minecraft.core.Registry<Biome> biomeRegistry;
    private final Biome defaultBiome;
    private final Mapper.BiomeEntry[] biomeEntries;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private static final boolean FORCE_LOD_MATERIAL = false;
    private static final String LOD_MATERIAL_KEY = "lod";
    private static final String LOD_SPRITE_KEY = "minecraft:block/white_wool";
    private static final long SPRITE_RANDOM_SEED = 42L;
    private static final boolean DISABLE_LOD_FACE_CROP = false;
    private static final Matrix4f BAKE_PROJ = new Matrix4f(
        2f, 0f, 0f, 0f,
        0f, 2f, 0f, 0f,
        0f, 0f, -1f, 0f,
        -1f, -1f, 0f, 1f
    );
    private static final Matrix4f[] BAKE_VIEWS = new Matrix4f[6];
    private static final float[][] FACE_UV_TEMPLATE = new float[6][8];

    private final Int2ObjectOpenHashMap<FaceSpriteSet> faceSpriteCache = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<List<ModelQuadTemplate>> modelQuadCache = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<float[]> colorCache = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Biome> biomeCache = new Int2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap tintCache = new Long2IntOpenHashMap();
    private final Int2ByteOpenHashMap fluidPresenceCache = new Int2ByteOpenHashMap();
    private final Int2IntOpenHashMap fluidBlockIdCache = new Int2IntOpenHashMap();
    private static final float[] WHITE_COLORS = GeometryUtil.whiteColor();

    private static final class MeshStats {
        int nonAirBlocks;
        int faces;
    }

    private static final class FaceSpriteSet {
        private final String[] faces;
        private final int[] tintIndices;
        private final String fallback;

        private FaceSpriteSet(String fallback, String[] faces, int[] tintIndices) {
            this.fallback = fallback;
            this.faces = faces;
            this.tintIndices = tintIndices;
        }
    }

    private static final class ModelQuadTemplate {
        private final String spriteKey;
        private final float[] localPositions;
        private final float[] uv;
        private final float[] normal;
        private final int tintIndex;

        private ModelQuadTemplate(String spriteKey, float[] localPositions, float[] uv, float[] normal, int tintIndex) {
            this.spriteKey = spriteKey;
            this.localPositions = localPositions;
            this.uv = uv;
            this.normal = normal;
            this.tintIndex = tintIndex;
        }
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink) {
        this(worldEngine, sink, 0, 0, 0, null, null);
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink, double offsetX, double offsetY, double offsetZ) {
        this(worldEngine, sink, offsetX, offsetY, offsetZ, null, null);
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink, double offsetX, double offsetY, double offsetZ, LodTextureProvider lodTextureProvider) {
        this(worldEngine, sink, offsetX, offsetY, offsetZ, lodTextureProvider, null);
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink, double offsetX, double offsetY, double offsetZ, LodTextureProvider lodTextureProvider, com.voxelbridge.export.ExportContext exportContext) {
        this.worldEngine = worldEngine;
        this.mapper = worldEngine.getMapper();
        this.sink = sink;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.lodTextureProvider = lodTextureProvider;
        this.exportContext = exportContext;
        this.blockColors = Minecraft.getInstance().getBlockColors();
        if (Minecraft.getInstance().level != null) {
            this.biomeRegistry = Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME);
            this.defaultBiome = this.biomeRegistry.getHolderOrThrow(Biomes.PLAINS).value();
        } else {
            this.biomeRegistry = null;
            this.defaultBiome = null;
        }
        this.biomeEntries = mapper.getBiomeEntries();
        this.tintCache.defaultReturnValue(Integer.MIN_VALUE);
        this.fluidPresenceCache.defaultReturnValue((byte) -1);
        this.fluidBlockIdCache.defaultReturnValue(Integer.MIN_VALUE);
    }

    static {
        addBakeView(0, -90, 0, 0, 0);
        addBakeView(1, 90, 0, 0, 0b100);
        addBakeView(2, 0, 180, 0, 0b001);
        addBakeView(3, 0, 0, 0, 0);
        // Keep WEST/EAST unrolled (rotation=0) to match bakery view orientation
        addBakeView(4, 0, 90, 0, 0b100);
        addBakeView(5, 0, 270, 0, 0);

        for (Direction dir : Direction.values()) {
            FACE_UV_TEMPLATE[dir.get3DDataValue()] = computeFaceUvTemplate(dir);
        }
    }

    public void meshChunk(int chunkX, int chunkY, int chunkZ, int level) {
        int scale = 1 << level;

        WorldSection section = worldEngine.acquire(level, chunkX, chunkY, chunkZ);
        WorldSection negX = worldEngine.acquireIfExists(level, chunkX - 1, chunkY, chunkZ);
        WorldSection posX = worldEngine.acquireIfExists(level, chunkX + 1, chunkY, chunkZ);
        WorldSection negY = worldEngine.acquireIfExists(level, chunkX, chunkY - 1, chunkZ);
        WorldSection posY = worldEngine.acquireIfExists(level, chunkX, chunkY + 1, chunkZ);
        WorldSection negZ = worldEngine.acquireIfExists(level, chunkX, chunkY, chunkZ - 1);
        WorldSection posZ = worldEngine.acquireIfExists(level, chunkX, chunkY, chunkZ + 1);
        if (section == null) {
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] mesh skip missing section lvl=%d pos=[%d,%d,%d]", level, chunkX, chunkY, chunkZ));
            return;
        }

        try {
            long[] data = section.copyData();
            long[] dataNegX = negX != null ? negX.copyData() : null;
            long[] dataPosX = posX != null ? posX.copyData() : null;
            long[] dataNegY = negY != null ? negY.copyData() : null;
            long[] dataPosY = posY != null ? posY.copyData() : null;
            long[] dataNegZ = negZ != null ? negZ.copyData() : null;
            long[] dataPosZ = posZ != null ? posZ.copyData() : null;
            MeshStats stats = new MeshStats();
            
            // Standard loop over 32x32x32 section
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int index = WorldSection.getIndex(x, y, z);
                        long blockIdLong = data[index];
                        
                        if (Mapper.isAir(blockIdLong)) continue;
                        stats.nonAirBlocks++;

                        int blockId = Mapper.getBlockId(blockIdLong);
                        int biomeId = Mapper.getBiomeId(blockIdLong);
                        BlockState state = mapper.getBlockStateFromBlockId(blockId);
                        if (state.getBlock() instanceof LiquidBlock) {
                            continue;
                        }
                        // Simple Culling: Check neighbors
                        // TODO: Check neighbors across section boundaries (requires acquiring neighbor sections)
                        // For now, we only cull internal to the section. Boundary faces will be generated.
                        
                        if (shouldUseModelQuads(state, blockId)) {
                            emitModelQuads(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, scale, stats);
                            continue;
                        }

                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.UP, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.UP, scale, stats);
                        }
                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.DOWN, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.DOWN, scale, stats);
                        }
                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.NORTH, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.NORTH, scale, stats);
                        }
                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.SOUTH, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.SOUTH, scale, stats);
                        }
                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.WEST, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.WEST, scale, stats);
                        }
                        if (shouldRenderFace(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ, x, y, z, Direction.EAST, state)) {
                            emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.EAST, scale, stats);
                        }
                    }
                }
            }
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] mesh section lvl=%d pos=[%d,%d,%d] nonAir=%d faces=%d",
                level, chunkX, chunkY, chunkZ, stats.nonAirBlocks, stats.faces));
            emitFluidFaces(data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ,
                chunkX, chunkY, chunkZ, scale, stats);

        } finally {
            if (negX != null) negX.release();
            if (posX != null) posX.release();
            if (negY != null) negY.release();
            if (posY != null) posY.release();
            if (negZ != null) negZ.release();
            if (posZ != null) posZ.release();
            section.release();
        }
    }

    private boolean shouldRenderFace(long[] data,
                                     long[] negX,
                                     long[] posX,
                                     long[] negY,
                                     long[] posY,
                                     long[] negZ,
                                     long[] posZ,
                                     int x, int y, int z, Direction dir, BlockState state) {
        int nx = x + dir.getStepX();
        int ny = y + dir.getStepY();
        int nz = z + dir.getStepZ();

        // If out of bounds of this section, render it (conservative approach)
        // If neighbor section exists, check it for occlusion.
        if (nx < 0 || nx >= 32 || ny < 0 || ny >= 32 || nz < 0 || nz >= 32) {
            long[] neighbor = null;
            int lx = x;
            int ly = y;
            int lz = z;
            switch (dir) {
                case WEST -> {
                    neighbor = negX;
                    lx = 31;
                }
                case EAST -> {
                    neighbor = posX;
                    lx = 0;
                }
                case DOWN -> {
                    neighbor = negY;
                    ly = 31;
                }
                case UP -> {
                    neighbor = posY;
                    ly = 0;
                }
                case NORTH -> {
                    neighbor = negZ;
                    lz = 31;
                }
                case SOUTH -> {
                    neighbor = posZ;
                    lz = 0;
                }
            }
            if (neighbor == null) {
                return true;
            }
            int neighborIdx = WorldSection.getIndex(lx, ly, lz);
            long neighborId = neighbor[neighborIdx];
            if (Mapper.isAir(neighborId)) return true;
            BlockState neighborState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(neighborId));
            if (state.skipRendering(neighborState, dir)) {
                return false;
            }
            if (neighborState.getRenderShape() == RenderShape.INVISIBLE) {
                return true;
            }
            return !isLodOccluding(neighborState);
        }

        int neighborIdx = WorldSection.getIndex(nx, ny, nz);
        long neighborId = data[neighborIdx];
        
        // If neighbor is air, we must render
        if (Mapper.isAir(neighborId)) return true;

        BlockState neighborState = mapper.getBlockStateFromBlockId(Mapper.getBlockId(neighborId));
        if (state.skipRendering(neighborState, dir)) {
            return false;
        }
        if (neighborState.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }
        return !isLodOccluding(neighborState);
    }

    private void emitFluidFaces(long[] data,
                                long[] negX,
                                long[] posX,
                                long[] negY,
                                long[] posY,
                                long[] negZ,
                                long[] posZ,
                                int chunkX,
                                int chunkY,
                                int chunkZ,
                                int scale,
                                MeshStats stats) {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int index = WorldSection.getIndex(x, y, z);
                    long blockIdLong = data[index];
                    if (Mapper.isAir(blockIdLong)) continue;
                    int blockId = Mapper.getBlockId(blockIdLong);
                    if (!blockHasFluid(blockId)) continue;

                    BlockState state = mapper.getBlockStateFromBlockId(blockId);
                    FluidState fluidState = state.getFluidState();
                    if (fluidState.isEmpty()) continue;

                    BlockState fluidBlock = state.getBlock() instanceof LiquidBlock
                        ? state
                        : fluidState.createLegacyBlock();
                    int fluidBlockId = state.getBlock() instanceof LiquidBlock
                        ? blockId
                        : resolveFluidBlockId(blockId, fluidBlock);
                    if (fluidBlockId <= 0) continue;

                    int biomeId = Mapper.getBiomeId(blockIdLong);

                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.UP)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.UP, scale, stats);
                    }
                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.DOWN)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.DOWN, scale, stats);
                    }
                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.NORTH)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.NORTH, scale, stats);
                    }
                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.SOUTH)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.SOUTH, scale, stats);
                    }
                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.WEST)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.WEST, scale, stats);
                    }
                    if (shouldRenderFluidFace(data, negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.EAST)) {
                        emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.EAST, scale, stats);
                    }
                }
            }
        }
    }

    private boolean shouldRenderFluidFace(long[] data,
                                          long[] negX,
                                          long[] posX,
                                          long[] negY,
                                          long[] posY,
                                          long[] negZ,
                                          long[] posZ,
                                          int x, int y, int z, Direction dir) {
        int nx = x + dir.getStepX();
        int ny = y + dir.getStepY();
        int nz = z + dir.getStepZ();
        return !hasFluidAt(data, negX, posX, negY, posY, negZ, posZ, nx, ny, nz);
    }

    private boolean hasFluidAt(long[] data,
                               long[] negX,
                               long[] posX,
                               long[] negY,
                               long[] posY,
                               long[] negZ,
                               long[] posZ,
                               int x, int y, int z) {
        if (x < 0) {
            return negX != null && hasFluidAt(negX, 31, y, z);
        }
        if (x >= 32) {
            return posX != null && hasFluidAt(posX, 0, y, z);
        }
        if (y < 0) {
            return negY != null && hasFluidAt(negY, x, 31, z);
        }
        if (y >= 32) {
            return posY != null && hasFluidAt(posY, x, 0, z);
        }
        if (z < 0) {
            return negZ != null && hasFluidAt(negZ, x, y, 31);
        }
        if (z >= 32) {
            return posZ != null && hasFluidAt(posZ, x, y, 0);
        }
        return hasFluidAt(data, x, y, z);
    }

    private boolean hasFluidAt(long[] data, int x, int y, int z) {
        int idx = WorldSection.getIndex(x, y, z);
        long id = data[idx];
        if (Mapper.isAir(id)) {
            return false;
        }
        return blockHasFluid(Mapper.getBlockId(id));
    }

    private static boolean isLodOccluding(BlockState state) {
        // Only treat SOLID layer as occluding; all other layers (cutout/cutoutMipped/translucent/tripwire) are non-occluding in LOD
        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
        if (layer != RenderType.solid()) {
            return false;
        }
        return state.canOcclude();
    }

    private boolean blockHasFluid(int blockId) {
        byte cached = fluidPresenceCache.get(blockId);
        if (cached != -1) {
            return cached == 1;
        }
        BlockState state = mapper.getBlockStateFromBlockId(blockId);
        boolean hasFluid = !state.getFluidState().isEmpty();
        fluidPresenceCache.put(blockId, (byte) (hasFluid ? 1 : 0));
        return hasFluid;
    }

    private int resolveFluidBlockId(int blockId, BlockState fluidBlock) {
        int cached = fluidBlockIdCache.get(blockId);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        int resolved = mapper.getIdForBlockState(fluidBlock);
        fluidBlockIdCache.put(blockId, resolved);
        return resolved;
    }

    private void emitFace(BlockState state, int blockId, int biomeId, int chunkX, int chunkY, int chunkZ, int localX, int localY, int localZ, Direction dir, int scale, MeshStats stats) {
        if (state.getRenderShape() == RenderShape.INVISIBLE && !(state.getBlock() instanceof LiquidBlock)) {
            return;
        }
        String materialKey;
        String spriteName;
        LodFaceMeta faceMeta = null;
        String overlaySprite = null;
        if (FORCE_LOD_MATERIAL) {
            materialKey = LOD_MATERIAL_KEY;
            spriteName = LOD_SPRITE_KEY;
        } else {
            spriteName = resolveSpriteKey(state, blockId, dir);
            materialKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (hasLodTextures(blockId) && lodTextureProvider instanceof LodFaceProvider faceProvider) {
                faceMeta = faceProvider.getFaceMeta(blockId, dir);
                if (lodTextureProvider instanceof LodOverlayProvider overlayProvider) {
                    overlaySprite = overlayProvider.getOverlaySpriteKey(blockId, dir);
                }
            }
        }
        if (DISABLE_LOD_FACE_CROP) {
            faceMeta = null;
        }

        int tintIndex = resolveTintIndex(state, blockId, dir);
        if (tintIndex < 0 && state.getBlock() instanceof LiquidBlock) {
            tintIndex = 0;
        }
        int tintColor = resolveTintColor(blockId, state, biomeId, tintIndex);
        boolean hasTint = tintColor != -1;

        if (faceMeta != null && faceMeta.hasBakedTint) {
            hasTint = false;
        }

        float[] baseColors = resolveColors(tintColor, hasTint);
        float[] overlayColors = baseColors;

        String combinedSprite = null;
        if (overlaySprite != null && exportContext != null) {
            combinedSprite = buildCombinedOverlaySprite(exportContext, spriteName, overlaySprite, tintColor);
            if (combinedSprite != null) {
                spriteName = combinedSprite;
                overlaySprite = null;
                hasTint = false;
                baseColors = WHITE_COLORS;
                overlayColors = WHITE_COLORS;
            } else {
                // overlay 单独着色，基底保持白色避免整面染色
                baseColors = WHITE_COLORS;
                overlayColors = resolveColors(tintColor, hasTint);
            }
        } else if (overlaySprite != null) {
            baseColors = WHITE_COLORS;
            overlayColors = resolveColors(tintColor, hasTint);
        }

        // Register tinted sprites to atlas for proper UV remapping
        if (exportContext != null) {
            if (combinedSprite != null) {
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, 0xFFFFFF);
            } else {
            // Overlay sprites must always be registered as tinted, even if tintColor is -1
            // This allows grass_block_side_overlay and similar textures to be properly separated
            boolean isBaseTinted = hasTint;
            boolean isOverlay = overlaySprite != null && isOverlaySprite(overlaySprite);
            boolean isBaseSpriteOverlay = isOverlaySprite(spriteName);

            if (isBaseTinted || isBaseSpriteOverlay) {
                int colorToRegister = (overlaySprite != null) ? 0xFFFFFF : (isBaseTinted ? tintColor : 0xFFFFFF);
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, colorToRegister);
            }

            if (overlaySprite != null) {
                // overlay 使用白色基底，着色交给顶点色
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, overlaySprite, 0xFFFFFF);
            }
            }
        }

        // Generate geometry for a standard unit face
        float[] positions = new float[12];
        float[] uvs = new float[8];
        float[] normals = new float[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()};
        
        // Basic Unit Cube Geometry
        float baseX = (float) ((chunkX * 32 + localX) * scale + offsetX);
        float baseY = (float) ((chunkY * 32 + localY) * scale + offsetY);
        float baseZ = (float) ((chunkZ * 32 + localZ) * scale + offsetZ);
        float x0 = baseX, x1 = baseX + scale;
        float y0 = baseY, y1 = baseY + scale;
        float z0 = baseZ, z1 = baseZ + scale;

        if (faceMeta != null && faceMeta.empty) {
            return;
        }

        // For UP/DOWN faces: u0/u1 map to X, v0/v1 map to Z, depth affects Y
        // For NORTH/SOUTH faces: u0/u1 map to X, v0/v1 map to Y, depth affects Z
        // For EAST/WEST faces: u0/u1 map to Z, v0/v1 map to Y, depth affects X

        float u0 = 0f;
        float u1 = 1f;
        float v0 = 0f;
        float v1 = 1f;
        float depthOffset = 0f;

        // UV coords for texture sampling - V is flipped because the saved image is Y-flipped
        float uvU0 = 0f;
        float uvU1 = 1f;
        float uvV0 = 0f;
        float uvV1 = 1f;

        if (faceMeta != null) {
            // Geometry cropping uses OpenGL texture coords (Y=0 at bottom, matches world Y)
            u0 = faceMeta.minX / 16.0f;
            u1 = (faceMeta.maxX + 1) / 16.0f;
            v0 = faceMeta.minY / 16.0f;
            v1 = (faceMeta.maxY + 1) / 16.0f;

            // UV sampling uses image coords (Y=0 at top, because image is Y-flipped when saved)
            // Flip V: if original minY=0,maxY=7 (bottom half in OpenGL),
            // after image flip it's at top half, so UV should be flipped
            uvU0 = u0;
            uvU1 = u1;
            uvV0 = 1.0f - v1;  // Flip: original maxY becomes UV minV
            uvV1 = 1.0f - v0;  // Flip: original minY becomes UV maxV

            depthOffset = clamp01(faceMeta.depth);
            if ((dir.get3DDataValue() & 1) == 1) {
                depthOffset = 1.0f - depthOffset;
            }
        }

        switch (dir) {
            case DOWN -> {
                float xx0 = lerp(x0, x1, u0);
                float xx1 = lerp(x0, x1, u1);
                float zz0 = lerp(z0, z1, v0);
                float zz1 = lerp(z0, z1, v1);
                float yy = faceMeta != null ? (baseY + depthOffset * scale) : y0;
                // y0 face: (x0, z1), (x0, z0), (x1, z0), (x1, z1)
                positions = new float[]{
                    xx0, yy, zz1,
                    xx0, yy, zz0,
                    xx1, yy, zz0,
                    xx1, yy, zz1
                };
            }
            case UP -> {
                float xx0 = lerp(x0, x1, u0);
                float xx1 = lerp(x0, x1, u1);
                float zz0 = lerp(z0, z1, v0);
                float zz1 = lerp(z0, z1, v1);
                float yy = faceMeta != null ? (baseY + depthOffset * scale) : y1;
                // y1 face: (x0, z0), (x0, z1), (x1, z1), (x1, z0)
                positions = new float[]{
                    xx0, yy, zz0,
                    xx0, yy, zz1,
                    xx1, yy, zz1,
                    xx1, yy, zz0
                };
            }
            case NORTH -> {
                float xx0 = lerp(x0, x1, u0);
                float xx1 = lerp(x0, x1, u1);
                float yy0 = lerp(y0, y1, v0);
                float yy1 = lerp(y0, y1, v1);
                float zz = faceMeta != null ? (baseZ + depthOffset * scale) : z0;
                // z0 face: (x1, y1), (x1, y0), (x0, y0), (x0, y1)
                positions = new float[]{
                    xx1, yy1, zz,
                    xx1, yy0, zz,
                    xx0, yy0, zz,
                    xx0, yy1, zz
                };
            }
            case SOUTH -> {
                float xx0 = lerp(x0, x1, u0);
                float xx1 = lerp(x0, x1, u1);
                float yy0 = lerp(y0, y1, v0);
                float yy1 = lerp(y0, y1, v1);
                float zz = faceMeta != null ? (baseZ + depthOffset * scale) : z1;
                // z1 face: (x0, y1), (x0, y0), (x1, y0), (x1, y1)
                positions = new float[]{
                    xx0, yy1, zz,
                    xx0, yy0, zz,
                    xx1, yy0, zz,
                    xx1, yy1, zz
                };
            }
            case WEST -> {
                float zz0 = lerp(z0, z1, u0);
                float zz1 = lerp(z0, z1, u1);
                float yy0 = lerp(y0, y1, v0);
                float yy1 = lerp(y0, y1, v1);
                float xx = faceMeta != null ? (baseX + depthOffset * scale) : x0;
                positions = new float[]{
                    xx, yy1, zz0,
                    xx, yy0, zz0,
                    xx, yy0, zz1,
                    xx, yy1, zz1
                };
            }
            case EAST -> {
                float zz0 = lerp(z0, z1, u0);
                float zz1 = lerp(z0, z1, u1);
                float yy0 = lerp(y0, y1, v0);
                float yy1 = lerp(y0, y1, v1);
                float xx = faceMeta != null ? (baseX + depthOffset * scale) : x1;
                // x1 face: (x1, y1, z0), (x1, y0, z0), (x1, y0, z1), (x1, y1, z1)
                positions = new float[]{
                    xx, yy1, zz1,
                    xx, yy0, zz1,
                    xx, yy0, zz0,
                    xx, yy1, zz0
                };
            }
        }
        
        float[] uvTemplate = FACE_UV_TEMPLATE[dir.get3DDataValue()];
        uvs = new float[]{
            lerp(uvU0, uvU1, uvTemplate[0]), lerp(uvV0, uvV1, uvTemplate[1]),
            lerp(uvU0, uvU1, uvTemplate[2]), lerp(uvV0, uvV1, uvTemplate[3]),
            lerp(uvU0, uvU1, uvTemplate[4]), lerp(uvV0, uvV1, uvTemplate[5]),
            lerp(uvU0, uvU1, uvTemplate[6]), lerp(uvV0, uvV1, uvTemplate[7])
        };

        sink.addQuad(
            materialKey,
            spriteName,
            null,
            positions,
            uvs,
            null,
            normals,
            baseColors,
            false
        );

        if (overlaySprite != null) {
            float eps = 0.001f * scale;
            float[] overlayPos = positions.clone();
            for (int i = 0; i < 4; i++) {
                int p = i * 3;
                overlayPos[p] += normals[0] * eps;
                overlayPos[p + 1] += normals[1] * eps;
                overlayPos[p + 2] += normals[2] * eps;
            }
            sink.addQuad(
                materialKey,
                overlaySprite,
                null,
                overlayPos,
                uvs,
                null,
                normals,
                overlayColors,
                false
            );
        }
        stats.faces++;
    }

    private boolean shouldUseModelQuads(BlockState state, int blockId) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (state.canOcclude()) {
            return false;
        }
        return !hasLodTextures(blockId);
    }

    private void emitModelQuads(BlockState state, int blockId, int biomeId, int chunkX, int chunkY, int chunkZ, int localX, int localY, int localZ, int scale, MeshStats stats) {
        List<ModelQuadTemplate> templates = modelQuadCache.get(blockId);
        if (templates == null) {
            templates = buildModelQuadTemplates(state);
            modelQuadCache.put(blockId, templates);
        }
        if (templates.isEmpty()) {
            return;
        }

        float baseX = (float) ((chunkX * 32 + localX) * scale + offsetX);
        float baseY = (float) ((chunkY * 32 + localY) * scale + offsetY);
        float baseZ = (float) ((chunkZ * 32 + localZ) * scale + offsetZ);

        String materialKey = FORCE_LOD_MATERIAL
            ? LOD_MATERIAL_KEY
            : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        for (ModelQuadTemplate template : templates) {
            float[] positions = new float[12];
            for (int i = 0; i < 4; i++) {
                int p = i * 3;
                positions[p] = baseX + template.localPositions[p] * scale;
                positions[p + 1] = baseY + template.localPositions[p + 1] * scale;
                positions[p + 2] = baseZ + template.localPositions[p + 2] * scale;
            }

            int tintColor = resolveTintColor(blockId, state, biomeId, template.tintIndex);
            float[] colors = resolveColors(tintColor, tintColor != -1);

            // Register tinted sprites to atlas for proper UV remapping
            String spriteKey = FORCE_LOD_MATERIAL ? LOD_SPRITE_KEY : template.spriteKey;
            if (exportContext != null) {
                // Always register overlay sprites, even if tintColor is -1
                boolean isTinted = tintColor != -1;
                boolean isOverlay = isOverlaySprite(spriteKey);

                if (isTinted || isOverlay) {
                    int colorToRegister = isTinted ? tintColor : 0xFFFFFF;
                    com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteKey, colorToRegister);
                }
            }

            sink.addQuad(
                materialKey,
                spriteKey,
                null,
                positions,
                template.uv,
                null,
                template.normal,
                colors,
                true
            );
            stats.faces++;
        }
    }

    private List<ModelQuadTemplate> buildModelQuadTemplates(BlockState state) {
        return Minecraft.getInstance().submit(() -> {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            RandomSource rand = RandomSource.create(SPRITE_RANDOM_SEED);
            List<BakedQuad> quads = new ArrayList<>();
            quads.addAll(model.getQuads(state, null, rand, ModelData.EMPTY, null));
            for (Direction dir : Direction.values()) {
                quads.addAll(model.getQuads(state, dir, rand, ModelData.EMPTY, null));
            }
            if (quads.isEmpty()) {
                return Collections.<ModelQuadTemplate>emptyList();
            }

            int stride = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
            int uvOffset = DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0) / 4;
            List<ModelQuadTemplate> templates = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                TextureAtlasSprite sprite = quad.getSprite();
                String spriteKey = sprite.contents().name().toString();
                int[] vertices = quad.getVertices();
                float[] localPos = new float[12];
                float[] atlasUv = new float[8];
                for (int i = 0; i < 4; i++) {
                    int base = i * stride;
                    localPos[i * 3] = Float.intBitsToFloat(vertices[base]);
                    localPos[i * 3 + 1] = Float.intBitsToFloat(vertices[base + 1]);
                    localPos[i * 3 + 2] = Float.intBitsToFloat(vertices[base + 2]);
                    atlasUv[i * 2] = Float.intBitsToFloat(vertices[base + uvOffset]);
                    atlasUv[i * 2 + 1] = Float.intBitsToFloat(vertices[base + uvOffset + 1]);
                }
                float[] uv = GeometryUtil.normalizeUVs(atlasUv, sprite);
                float[] normal = GeometryUtil.computeFaceNormal(localPos);
                templates.add(new ModelQuadTemplate(spriteKey, localPos, uv, normal, quad.getTintIndex()));
            }
            return templates;
        }).join();
    }

    private String resolveSpriteKey(BlockState state, int blockId, Direction dir) {
        if (hasLodTextures(blockId)) {
            String spriteKey = lodTextureProvider.getSpriteKey(blockId, dir);
            if (spriteKey != null) {
                return spriteKey;
            }
        }
        FaceSpriteSet cached = faceSpriteCache.get(blockId);
        if (cached == null) {
            cached = buildFaceSprites(state);
            faceSpriteCache.put(blockId, cached);
        }
        String sprite = cached.faces[dir.ordinal()];
        return sprite != null ? sprite : cached.fallback;
    }

    private int resolveTintIndex(BlockState state, int blockId, Direction dir) {
        FaceSpriteSet cached = faceSpriteCache.get(blockId);
        if (cached == null) {
            cached = buildFaceSprites(state);
            faceSpriteCache.put(blockId, cached);
        }
        return cached.tintIndices[dir.ordinal()];
    }

    private boolean hasLodTextures(int blockId) {
        return lodTextureProvider != null && lodTextureProvider.hasTextures(blockId);
    }

    private FaceSpriteSet buildFaceSprites(BlockState state) {
        return Minecraft.getInstance().submit(() -> {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite particle = model.getParticleIcon();
            String fallback = particle.contents().name().toString();
            String[] faces = new String[6];
            int[] tintIndices = new int[6];
            for (int i = 0; i < tintIndices.length; i++) {
                tintIndices[i] = -1;
            }
            for (Direction dir : Direction.values()) {
                RandomSource rand = RandomSource.create(SPRITE_RANDOM_SEED);
                List<BakedQuad> quads = model.getQuads(state, dir, rand, ModelData.EMPTY, null);
                if (!quads.isEmpty()) {
                    TextureAtlasSprite sprite = quads.get(0).getSprite();
                    faces[dir.ordinal()] = sprite.contents().name().toString();
                    tintIndices[dir.ordinal()] = pickTintIndex(quads);
                } else {
                    faces[dir.ordinal()] = fallback;
                }
            }
            // 如果方向性 quad 为空，尝试使用通用（dir=null）的 quad 作为兜底，确保带 tint 的植被类有颜色
            boolean needGeneral = false;
            for (int i = 0; i < faces.length; i++) {
                if (faces[i] == null || faces[i].equals(fallback)) {
                    needGeneral = true;
                    break;
                }
            }
            if (needGeneral) {
                List<BakedQuad> general = model.getQuads(state, null, RandomSource.create(SPRITE_RANDOM_SEED), ModelData.EMPTY, null);
                if (!general.isEmpty()) {
                    TextureAtlasSprite sprite = general.get(0).getSprite();
                    String spriteName = sprite.contents().name().toString();
                    int tintIdx = pickTintIndex(general);
                    for (Direction dir : Direction.values()) {
                        int idx = dir.ordinal();
                        if (faces[idx] == null || faces[idx].equals(fallback)) {
                            faces[idx] = spriteName;
                            tintIndices[idx] = tintIdx;
                        }
                    }
                }
            }
            return new FaceSpriteSet(fallback, faces, tintIndices);
        }).join();
    }

    private static int pickTintIndex(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.getTintIndex();
            if (tintIndex >= 0) {
                return tintIndex;
            }
        }
        return -1;
    }

    private int resolveTintColor(int blockId, BlockState state, int biomeId, int tintIndex) {
        if (tintIndex < 0 || blockColors == null || biomeRegistry == null) {
            return -1;
        }
        long key = ((long) blockId << 32) | ((long) biomeId << 16) | (tintIndex & 0xFFFFL);
        int cached = tintCache.get(key);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }

        int color = Minecraft.getInstance().submit(() -> {
            Biome biome = resolveBiome(biomeId);
            if (biome == null) {
                return -2; // Special marker for not found
            }
            LodTintGetter getter = new LodTintGetter(state, biome);
            return blockColors.getColor(state, getter, BlockPos.ZERO, tintIndex);
        }).join();

        if (color == -2) {
            tintCache.put(key, -1);
            return -1;
        }

        int argb = color == -1 ? -1 : (color | 0xFF000000);
        tintCache.put(key, argb);
        return argb;
    }

    private Biome resolveBiome(int biomeId) {
        if (biomeId < 0 || biomeId >= biomeEntries.length) {
            return defaultBiome;
        }
        Biome cached = biomeCache.get(biomeId);
        if (cached != null) {
            return cached;
        }
        Mapper.BiomeEntry entry = biomeEntries[biomeId];
        if (entry == null || entry.biome == null) {
            return defaultBiome;
        }
        Biome biome = biomeRegistry.get(ResourceLocation.parse(entry.biome));
        if (biome == null) {
            biome = defaultBiome;
        }
        if (biome != null) {
            biomeCache.put(biomeId, biome);
        }
        return biome;
    }

    private float[] resolveColors(int tintColor, boolean hasTint) {
        if (!hasTint || tintColor == -1) {
            return WHITE_COLORS;
        }
        float[] cached = colorCache.get(tintColor);
        if (cached != null) {
            return cached;
        }
        float[] colors = GeometryUtil.computeVertexColors(tintColor, true);
        colorCache.put(tintColor, colors);
        return colors;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static void addBakeView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        BAKE_VIEWS[i] = new Matrix4f(stack.last().pose());
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

    private static float[] computeFaceUvTemplate(Direction dir) {
        float[][] corners;
        switch (dir) {
            case DOWN -> corners = new float[][]{
                {0f, 0f, 1f},
                {0f, 0f, 0f},
                {1f, 0f, 0f},
                {1f, 0f, 1f}
            };
            case UP -> corners = new float[][]{
                {0f, 1f, 0f},
                {0f, 1f, 1f},
                {1f, 1f, 1f},
                {1f, 1f, 0f}
            };
            case NORTH -> corners = new float[][]{
                {1f, 1f, 0f},
                {1f, 0f, 0f},
                {0f, 0f, 0f},
                {0f, 1f, 0f}
            };
            case SOUTH -> corners = new float[][]{
                {0f, 1f, 1f},
                {0f, 0f, 1f},
                {1f, 0f, 1f},
                {1f, 1f, 1f}
            };
            case WEST -> corners = new float[][]{
                {0f, 1f, 0f},
                {0f, 0f, 0f},
                {0f, 0f, 1f},
                {0f, 1f, 1f}
            };
            case EAST -> corners = new float[][]{
                {1f, 1f, 1f},
                {1f, 0f, 1f},
                {1f, 0f, 0f},
                {1f, 1f, 0f}
            };
            default -> corners = new float[][]{
                {0f, 0f, 0f},
                {0f, 0f, 0f},
                {0f, 0f, 0f},
                {0f, 0f, 0f}
            };
        }

        Matrix4f transform = new Matrix4f(BAKE_PROJ).mul(BAKE_VIEWS[dir.get3DDataValue()]);
        float[] uvTemplate = new float[8];
        Vector4f tmp = new Vector4f();
        for (int i = 0; i < 4; i++) {
            float[] c = corners[i];
            tmp.set(c[0], c[1], c[2], 1f);
            transform.transform(tmp);
            float ndcX = tmp.x / tmp.w;
            float ndcY = tmp.y / tmp.w;
            float u = (ndcX + 1f) * 0.5f;
            float v = 1f - (ndcY + 1f) * 0.5f;
            uvTemplate[i * 2] = clamp01(u);
            uvTemplate[i * 2 + 1] = clamp01(v);
        }
        return uvTemplate;
    }

    private static final class LodTintGetter implements BlockAndTintGetter {
        private final BlockState state;
        private final Biome biome;

        private LodTintGetter(BlockState state, Biome biome) {
            this.state = state;
            this.biome = biome;
        }

        @Override
        public float getShade(Direction direction, boolean shaded) {
            return 0;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver colorResolver) {
            return colorResolver.getColor(biome, 0, 0);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
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
    }

    /**
     * Check if a sprite key represents an overlay texture.
     * Overlay textures (like grass_block_side_overlay) need special handling for tint.
     */
    private static boolean isOverlaySprite(String spriteKey) {
        if (spriteKey == null) {
            return false;
        }
        return spriteKey.contains("overlay");
    }

    private static String buildCombinedOverlaySprite(ExportContext ctx, String baseKey, String overlayKey, int tintColor) {
        if (ctx == null || baseKey == null || overlayKey == null) {
            return null;
        }
        int color = tintColor == -1 ? 0xFFFFFF : (tintColor & 0xFFFFFF);
        String combinedKey = baseKey + "|overlay|" + overlayKey + "|tint|" + String.format("%06X", color);
        if (ctx.getCachedSpriteImage(combinedKey) != null) {
            return combinedKey;
        }
        BufferedImage base = ctx.getCachedSpriteImage(baseKey);
        BufferedImage overlay = ctx.getCachedSpriteImage(overlayKey);
        if (base == null || overlay == null) {
            return null;
        }
        int w = base.getWidth();
        int h = base.getHeight();
        if (overlay.getWidth() != w || overlay.getHeight() != h) {
            return null;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] basePixels = base.getRGB(0, 0, w, h, null, 0, w);
        int[] overlayPixels = overlay.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[basePixels.length];

        int rMul = (color >> 16) & 0xFF;
        int gMul = (color >> 8) & 0xFF;
        int bMul = color & 0xFF;

        for (int i = 0; i < basePixels.length; i++) {
            int baseArgb = basePixels[i];
            int overArgb = overlayPixels[i];
            int oa = (overArgb >>> 24) & 0xFF;
            if (oa == 0) {
                outPixels[i] = baseArgb;
                continue;
            }
            int or = (overArgb >>> 16) & 0xFF;
            int og = (overArgb >>> 8) & 0xFF;
            int ob = overArgb & 0xFF;

            or = (or * rMul) / 255;
            og = (og * gMul) / 255;
            ob = (ob * bMul) / 255;

            int ba = (baseArgb >>> 24) & 0xFF;
            int br = (baseArgb >>> 16) & 0xFF;
            int bg = (baseArgb >>> 8) & 0xFF;
            int bb = baseArgb & 0xFF;

            int inv = 255 - oa;
            int outA = oa + (ba * inv) / 255;
            int outR = (or * oa + br * inv) / 255;
            int outG = (og * oa + bg * inv) / 255;
            int outB = (ob * oa + bb * inv) / 255;

            outPixels[i] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        ctx.cacheSpriteImage(combinedKey, out);
        return combinedKey;
    }

    // Removed emitDebugQuad

}

