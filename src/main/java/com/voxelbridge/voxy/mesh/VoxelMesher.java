package com.voxelbridge.voxy.mesh;

import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.util.geometry.GeometryUtil;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.common.world.WorldSection;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.export.texture.TextureLoader;
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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
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
    private final WorldEngine worldEngine;
    private final Mapper mapper;
    private final SceneSink sink;
    private final LodTextureProvider lodTextureProvider;
    private final com.voxelbridge.export.ExportContext exportContext;
    private final RandomSource random = RandomSource.create();
    // 流体掩码：索引 y*32+z，每个 int 的 32bit 对应 x 轴是否含流体（水logged 也算）
    private final int[] fluidMasks = new int[32 * 32];
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
    private boolean forceIndividualAtlas = false;
    // Cross-LOD caches
    private final Int2ByteOpenHashMap occlusionCache = new Int2ByteOpenHashMap();
    private final long[][] fineNeighborSnapshots = new long[24][];
    private Set<Long> sectionsToMesh;
    private static final int FACE_DECISION_SKIP = -1;
    private static final int FACE_DECISION_FULL = 16;
    private static final float[] WHITE_COLORS = GeometryUtil.whiteColor();
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

    private static final class MeshStats {
        int nonAirBlocks;
        int faces;
    }

    private static final class FaceSpriteSet {
        private final String[] faces;
        private final int[] tintIndices;
        private final int[] baseTintIndices;
        private final String fallback;

        private FaceSpriteSet(String fallback, String[] faces, int[] tintIndices, int[] baseTintIndices) {
            this.fallback = fallback;
            this.faces = faces;
            this.tintIndices = tintIndices;
            this.baseTintIndices = baseTintIndices;
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
        this.occlusionCache.defaultReturnValue((byte) -1);
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

    public void setSectionsToMesh(Set<Long> sectionsToMesh) {
        this.sectionsToMesh = sectionsToMesh;
    }

    private boolean isIndividualAtlasMode() {
        return exportContext != null
            && (forceIndividualAtlas || ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
    }

    // --- Cross-LOD helpers ---
    private void snapshotFineNeighbors(int chunkX, int chunkY, int chunkZ, int fineLevel) {
        int baseX = chunkX * 2;
        int baseY = chunkY * 2;
        int baseZ = chunkZ * 2;

        snapshotFineQuad(0, fineLevel, baseX, baseY - 1, baseZ, Direction.DOWN);
        snapshotFineQuad(4, fineLevel, baseX, baseY + 2, baseZ, Direction.UP);
        snapshotFineQuad(8, fineLevel, baseX, baseY, baseZ - 1, Direction.NORTH);
        snapshotFineQuad(12, fineLevel, baseX, baseY, baseZ + 2, Direction.SOUTH);
        snapshotFineQuad(16, fineLevel, baseX - 1, baseY, baseZ, Direction.WEST);
        snapshotFineQuad(20, fineLevel, baseX + 2, baseY, baseZ, Direction.EAST);
    }

    private void snapshotFineQuad(int offset, int level, int startX, int startY, int startZ, Direction dirFromCurrent) {
        int dirIndex = offset / 4;
        for (int i = 0; i < 4; i++) {
            int dx = 0, dy = 0, dz = 0;
            int u = (i & 1);
            int v = (i >> 1) & 1;
            switch (dirIndex) {
                case 0, 1 -> { dx = u; dz = v; }
                case 2, 3 -> { dx = u; dy = v; }
                case 4, 5 -> { dy = u; dz = v; }
            }
            WorldSection section = worldEngine.acquireIfExists(level, startX + dx, startY + dy, startZ + dz);
            if (section != null) {
                try {
                    fineNeighborSnapshots[offset + i] = extractFaceData(section, dirFromCurrent);
                } finally {
                    section.release();
                }
            } else {
                fineNeighborSnapshots[offset + i] = null;
            }
        }
    }

    private long[] extractFaceData(WorldSection section, Direction dirFromCurrent) {
        long[] snapshot = new long[32 * 32];
        long[] src = section._unsafeGetRawDataArray();
        boolean isPositiveDir = dirFromCurrent.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int fixedCoord = isPositiveDir ? 0 : 31;

        if (dirFromCurrent.getAxis() == Direction.Axis.Y) {
            int start = fixedCoord << 10;
            System.arraycopy(src, start, snapshot, 0, 1024);
        } else if (dirFromCurrent.getAxis() == Direction.Axis.Z) {
            int idx = 0;
            for (int y = 0; y < 32; y++) {
                int yBase = (y << 10) | (fixedCoord << 5);
                System.arraycopy(src, yBase, snapshot, idx, 32);
                idx += 32;
            }
        } else {
            int idx = 0;
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    int srcIdx = (y << 10) | (z << 5) | fixedCoord;
                    snapshot[idx++] = src[srcIdx];
                }
            }
        }
        return snapshot;
    }

    private void releaseFineNeighbors() {
        Arrays.fill(fineNeighborSnapshots, null);
    }

    private int computeFineOcclusionMask(int localX, int localY, int localZ, Direction dir) {
        int u = 0, v = 0;
        int fineU = 0, fineV = 0;
        switch (dir) {
            case DOWN, UP -> {
                u = (localX >= 16) ? 1 : 0;
                v = (localZ >= 16) ? 1 : 0;
                fineU = (localX * 2) % 32;
                fineV = (localZ * 2) % 32;
            }
            case NORTH, SOUTH -> {
                u = (localX >= 16) ? 1 : 0;
                v = (localY >= 16) ? 1 : 0;
                fineU = (localX * 2) % 32;
                fineV = (localY * 2) % 32;
            }
            case WEST, EAST -> {
                u = (localY >= 16) ? 1 : 0;
                v = (localZ >= 16) ? 1 : 0;
                fineU = (localY * 2) % 32;
                fineV = (localZ * 2) % 32;
            }
        }

        int chunkIdx = dir.ordinal() * 4 + (u + v * 2);
        long[] snapshot = fineNeighborSnapshots[chunkIdx];
        if (snapshot == null) return 0;

        int mask = 0;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                int du = fineU + i;
                int dv = fineV + j;
                int snapIdx;
                if (dir.getAxis() == Direction.Axis.Y) {
                    snapIdx = (dv << 5) | du;
                } else if (dir.getAxis() == Direction.Axis.Z) {
                    snapIdx = (dv << 5) | du;
                } else {
                    snapIdx = (du << 5) | dv;
                }
                long id = snapshot[snapIdx];
                if (!Mapper.isAir(id)) {
                    int blockId = Mapper.getBlockId(id);
                    if (isOccluding(blockId)) {
                        int bit = (j << 1) | i;
                        mask |= (1 << bit);
                    }
                }
            }
        }
        return mask;
    }

    private boolean hasFineSnapshot(Direction dir, int localX, int localY, int localZ) {
        int u = 0, v = 0;
        switch (dir) {
            case DOWN, UP -> {
                u = (localX >= 16) ? 1 : 0;
                v = (localZ >= 16) ? 1 : 0;
            }
            case NORTH, SOUTH -> {
                u = (localX >= 16) ? 1 : 0;
                v = (localY >= 16) ? 1 : 0;
            }
            case WEST, EAST -> {
                u = (localY >= 16) ? 1 : 0;
                v = (localZ >= 16) ? 1 : 0;
            }
        }
        int chunkIdx = dir.ordinal() * 4 + (u + v * 2);
        return fineNeighborSnapshots[chunkIdx] != null;
    }

    private boolean isOccluding(int blockId) {
        byte cached = occlusionCache.get(blockId);
        if (cached != -1) {
            return cached == 1;
        }
        BlockState state = mapper.getBlockStateFromBlockId(blockId);
        boolean occ = state.canOcclude();
        occlusionCache.put(blockId, (byte) (occ ? 1 : 0));
        return occ;
    }

    public void meshChunk(int chunkX, int chunkY, int chunkZ, int level) {
        boolean greedyEnabled = ExportRuntimeConfig.isLodGreedyMeshingEnabled();
        boolean prevForceIndividual = this.forceIndividualAtlas;
        this.forceIndividualAtlas = greedyEnabled || ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.INDIVIDUAL;
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
            Arrays.fill(this.fluidMasks, 0);
            int originX = chunkX * 32;
            int originY = chunkY * 32;
            int originZ = chunkZ * 32;
            LodBlockGetter blockGetter = new LodBlockGetter(mapper, originX, originY, originZ,
                data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
            if (level > 0) {
                snapshotFineNeighbors(chunkX, chunkY, chunkZ, level - 1);
            }
            int[][] greedyFaceIds = null;
            List<GreedyFacePayload>[] greedyPayloads = null;
            Map<GreedyFaceKey, Integer>[] greedyPayloadMaps = null;
            if (greedyEnabled) {
                greedyFaceIds = new int[6][32 * 32 * 32];
                greedyPayloads = new List[6];
                greedyPayloadMaps = new Map[6];
                for (int i = 0; i < 6; i++) {
                    greedyPayloads[i] = new ArrayList<>();
                    greedyPayloadMaps[i] = new HashMap<>();
                }
            }

            // Standard loop over 32x32x32 section
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int index = WorldSection.getIndex(x, y, z);
                        long blockIdLong = data[index];

                        if (Mapper.isAir(blockIdLong)) continue;
                        stats.nonAirBlocks++;
                        pos.set(originX + x, originY + y, originZ + z);

                        int blockId = Mapper.getBlockId(blockIdLong);
                        int biomeId = Mapper.getBiomeId(blockIdLong);
                        BlockState state = mapper.getBlockStateFromBlockId(blockId);
                        // 记录流体掩码（含 waterlogged）
                        if (!state.getFluidState().isEmpty()) {
                            int mIdx = y * 32 + z;
                            fluidMasks[mIdx] |= (1 << x);
                        }
                        if (state.getBlock() instanceof LiquidBlock) {
                            continue;
                        }
                        // 使用 LodBlockGetter + Block.shouldRenderFace 进行可见性剔除，
                        // 支持跨 section 邻居（若邻居缺失则视为空气）。

                        if (shouldUseModelQuads(state, blockId)) {
                            emitModelQuads(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, scale, stats);
                            continue;
                        }

                        int upDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.UP, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (upDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && upDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.UP, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.UP, scale, upDecision, stats);
                            }
                        }
                        int downDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.DOWN, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (downDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && downDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.DOWN, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.DOWN, scale, downDecision, stats);
                            }
                        }
                        int northDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.NORTH, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (northDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && northDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.NORTH, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.NORTH, scale, northDecision, stats);
                            }
                        }
                        int southDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.SOUTH, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (southDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && southDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.SOUTH, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.SOUTH, scale, southDecision, stats);
                            }
                        }
                        int westDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.WEST, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (westDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && westDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.WEST, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.WEST, scale, westDecision, stats);
                            }
                        }
                        int eastDecision = shouldRenderFace(blockGetter, pos, neighborPos, Direction.EAST, state, level, chunkX, chunkY, chunkZ, x, y, z);
                        if (eastDecision != FACE_DECISION_SKIP) {
                            boolean queued = greedyEnabled && eastDecision == FACE_DECISION_FULL &&
                                tryQueueGreedyFace(Direction.EAST, state, blockId, biomeId, x, y, z, greedyFaceIds, greedyPayloads, greedyPayloadMaps);
                            if (!queued) {
                                emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, Direction.EAST, scale, eastDecision, stats);
                            }
                        }
                    }
                }
            }
            if (greedyEnabled) {
                emitGreedyFaces(greedyFaceIds, greedyPayloads, chunkX, chunkY, chunkZ, scale, stats);
            }
            emitFluidFaces(blockGetter, pos, neighborPos,
                data, dataNegX, dataPosX, dataNegY, dataPosY, dataNegZ, dataPosZ,
                chunkX, chunkY, chunkZ, scale, stats);
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] mesh section lvl=%d pos=[%d,%d,%d] nonAir=%d faces=%d",
                level, chunkX, chunkY, chunkZ, stats.nonAirBlocks, stats.faces));

        } finally {
            this.forceIndividualAtlas = prevForceIndividual;
            releaseFineNeighbors();
            occlusionCache.clear();
            if (negX != null) negX.release();
            if (posX != null) posX.release();
            if (negY != null) negY.release();
            if (posY != null) posY.release();
            if (negZ != null) negZ.release();
            if (posZ != null) posZ.release();
            section.release();
        }
    }

    private boolean tryQueueGreedyFace(Direction dir,
                                       BlockState state,
                                       int blockId,
                                       int biomeId,
                                       int localX,
                                       int localY,
                                       int localZ,
                                       int[][] greedyFaceIds,
                                       List<GreedyFacePayload>[] greedyPayloads,
                                       Map<GreedyFaceKey, Integer>[] greedyPayloadMaps) {
        GreedyFacePayload payload = buildGreedyPayload(state, blockId, biomeId, dir);
        if (payload == null) {
            return false;
        }
        GreedyFaceKey key = payload.key;
        Map<GreedyFaceKey, Integer> map = greedyPayloadMaps[dir.ordinal()];
        Integer id = map.get(key);
        if (id == null) {
            List<GreedyFacePayload> list = greedyPayloads[dir.ordinal()];
            list.add(payload);
            id = list.size();
            map.put(key, id);
        }
        int idx = greedyGridIndex(dir, localX, localY, localZ);
        greedyFaceIds[dir.ordinal()][idx] = id;
        return true;
    }

    private GreedyFacePayload buildGreedyPayload(BlockState state, int blockId, int biomeId, Direction dir) {
        if (state.getRenderShape() == RenderShape.INVISIBLE && !(state.getBlock() instanceof LiquidBlock)) {
            return null;
        }
        String materialKey;
        String spriteName;
        LodFaceMeta faceMeta = null;
        String overlaySprite = null;
        boolean disableLodTexture = state.getBlock() instanceof LiquidBlock;
        if (FORCE_LOD_MATERIAL) {
            materialKey = LOD_MATERIAL_KEY;
            spriteName = LOD_SPRITE_KEY;
        } else {
            if (disableLodTexture) {
                spriteName = resolveSpriteKeyVanilla(state, blockId, dir);
            } else {
                spriteName = resolveSpriteKey(state, blockId, dir);
                if (hasLodTextures(blockId) && lodTextureProvider instanceof LodFaceProvider faceProvider) {
                    faceMeta = faceProvider.getFaceMeta(blockId, dir);
                    if (lodTextureProvider instanceof LodOverlayProvider overlayProvider) {
                        overlaySprite = overlayProvider.getOverlaySpriteKey(blockId, dir);
                    }
                }
            }
            materialKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }
        if (faceMeta != null) {
            // 仅当裁剪覆盖整块时才允许贪婪合并，否则保留原路径以避免 UV 失真
            boolean fullFace = faceMeta.empty == false
                && faceMeta.minX == 0 && faceMeta.maxX == 15
                && faceMeta.minY == 0 && faceMeta.maxY == 15;
            if (!fullFace) {
                return null;
            }
        }

        int tintIndex = resolveTintIndex(state, blockId, dir);
        if (tintIndex < 0 && state.getBlock() instanceof LiquidBlock) {
            tintIndex = 0;
        }
        int tintColor = resolveTintColor(blockId, state, biomeId, tintIndex);
        boolean hasTint = tintColor != -1;

        int baseTintColor;
        int overlayTintColor;

        if (overlaySprite != null) {
            boolean isGrassBlock = state.getBlock() == Blocks.GRASS_BLOCK;
            boolean isOverlayType = isOverlaySprite(overlaySprite);

            if ((isGrassBlock && dir.getAxis().isHorizontal()) || isOverlayType) {
                baseTintColor = -1;
                overlayTintColor = tintColor;
            } else {
                baseTintColor = tintColor;
                overlayTintColor = -1;
            }
        } else {
            baseTintColor = tintColor;
            overlayTintColor = -1;
        }

        float[] baseColors = WHITE_COLORS;
        float[] overlayColors = WHITE_COLORS;
        String combinedSprite = null;

        if (overlaySprite != null && exportContext != null) {
            combinedSprite = buildLayeredTintedSprite(exportContext, spriteName, baseTintColor, overlaySprite, overlayTintColor);
            if (combinedSprite != null) {
                spriteName = combinedSprite;
                overlaySprite = null;
                baseColors = WHITE_COLORS;
            } else {
                baseColors = resolveColors(baseTintColor, baseTintColor != -1);
                overlayColors = resolveColors(overlayTintColor, overlayTintColor != -1);
            }
        } else {
            if (hasTint) {
                baseColors = resolveColors(baseTintColor, true);
            } else {
                baseColors = WHITE_COLORS;
            }
        }

        if (exportContext != null) {
            if (combinedSprite != null) {
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, 0xFFFFFF);
            } else {
                boolean isBaseTinted = baseTintColor != -1;
                boolean isOverlay = overlaySprite != null && overlayTintColor != -1;
                boolean isBaseSpriteOverlay = isOverlaySprite(spriteName);

                if (isBaseTinted || isBaseSpriteOverlay) {
                    int colorToRegister = (overlaySprite != null) ? 0xFFFFFF : (isBaseTinted ? baseTintColor : 0xFFFFFF);
                    com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, colorToRegister);
                }

                if (overlaySprite != null) {
                    int overlayRegColor = overlayTintColor != -1 ? overlayTintColor : 0xFFFFFF;
                    com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, overlaySprite, overlayRegColor);
                }
            }
        }

        boolean individualMode = isIndividualAtlasMode();
        if (individualMode) {
            materialKey = overlaySprite != null ? (spriteName + "+ov+" + overlaySprite) : spriteName;
        }

        // Default full-face UVs; tiling spans are computed in emitGreedyQuad.
        float u0 = 0f;
        float u1 = 1f;
        float v0 = 0f;
        float v1 = 1f;
        float uvU0 = 0f;
        float uvU1 = 1f;
        float uvV0 = 0f;
        float uvV1 = 1f;

        return new GreedyFacePayload(
            new GreedyFaceKey(blockId, materialKey, spriteName, overlaySprite, baseTintColor, overlayTintColor),
            baseColors,
            overlayColors,
            u0, u1, v0, v1,
            uvU0, uvU1, uvV0, uvV1
        );
    }

    private void emitGreedyFaces(int[][] faceIds,
                                 List<GreedyFacePayload>[] payloads,
                                 int chunkX,
                                 int chunkY,
                                 int chunkZ,
                                 int scale,
                                 MeshStats stats) {
        for (Direction dir : Direction.values()) {
            int dirIdx = dir.ordinal();
            int[] grid = faceIds[dirIdx];
            List<GreedyFacePayload> plist = payloads[dirIdx];
            if (grid == null || plist == null || plist.isEmpty()) {
                continue;
            }
            GreedyLayerMesher mesher = new GreedyLayerMesher(dir, plist, chunkX, chunkY, chunkZ, scale, stats);
            for (int layer = 0; layer < 32; layer++) {
                mesher.setLayer(layer);
                for (int v = 0; v < 32; v++) {
                    int rowBase = (layer * 32 + v) * 32;
                    for (int u = 0; u < 32; u++) {
                        mesher.putNext(grid[rowBase + u]);
                    }
                    mesher.endRow();
                }
                mesher.finishLayer();
            }
        }
    }

    private static int greedyGridIndex(Direction dir, int localX, int localY, int localZ) {
        int layer;
        int u;
        int v;
        switch (dir) {
            case DOWN, UP -> {
                layer = localY;
                u = localX;
                v = localZ;
            }
            case NORTH, SOUTH -> {
                layer = localZ;
                u = localX;
                v = localY;
            }
            case WEST, EAST -> {
                layer = localX;
                u = localZ;
                v = localY;
            }
            default -> throw new IllegalStateException("Unexpected value: " + dir);
        }
        return (layer * 32 + v) * 32 + u;
    }

    private final class GreedyLayerMesher extends ScanMesher2D {
        private final Direction dir;
        private final List<GreedyFacePayload> payloads;
        private final int chunkX;
        private final int chunkY;
        private final int chunkZ;
        private final int scale;
        private final MeshStats stats;
        private int layer;

        private GreedyLayerMesher(Direction dir, List<GreedyFacePayload> payloads, int chunkX, int chunkY, int chunkZ, int scale, MeshStats stats) {
            this.dir = dir;
            this.payloads = payloads;
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.scale = scale;
            this.stats = stats;
        }

        private void setLayer(int layer) {
            this.layer = layer;
        }

        private void finishLayer() {
            this.finish();
        }

        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            if (data == 0) {
                return;
            }
            GreedyFacePayload payload = this.payloads.get((int) data - 1);
            int startU = x - (length - 1);
            int startV = z - (width - 1);
            emitGreedyQuad(payload, this.dir, this.layer, startU, startV, length, width, this.chunkX, this.chunkY, this.chunkZ, this.scale, this.stats);
        }
    }

    private void emitGreedyQuad(GreedyFacePayload payload,
                                Direction dir,
                                int layer,
                                int startU,
                                int startV,
                                int length,
                                int width,
                                int chunkX,
                                int chunkY,
                                int chunkZ,
                                int scale,
                                MeshStats stats) {
        // 对贪婪合并的面，UV 按方块重复而非拉伸，保持与非贪婪一致的平铺密度
        float u0 = 0f;
        float u1 = 1f;
        float v0 = 0f;
        float v1 = 1f;
        float uvU0 = startU;
        float uvU1 = startU + length;
        float uvV0 = startV;
        float uvV1 = startV + width;

        int localX;
        int localY;
        int localZ;
        switch (dir) {
            case DOWN, UP -> {
                localX = startU;
                localY = layer;
                localZ = startV;
            }
            case NORTH, SOUTH -> {
                localX = startU;
                localY = startV;
                localZ = layer;
            }
            case WEST, EAST -> {
                localX = layer;
                localY = startV;
                localZ = startU;
            }
            default -> throw new IllegalStateException("Unexpected value: " + dir);
        }

        float baseX = (float) ((chunkX * 32 + localX) * scale + offsetX);
        float baseY = (float) ((chunkY * 32 + localY) * scale + offsetY);
        float baseZ = (float) ((chunkZ * 32 + localZ) * scale + offsetZ);

        float x1;
        float y1;
        float z1;
        switch (dir) {
            case DOWN, UP -> {
                x1 = baseX + length * scale;
                y1 = baseY + scale;
                z1 = baseZ + width * scale;
            }
            case NORTH, SOUTH -> {
                x1 = baseX + length * scale;
                y1 = baseY + width * scale;
                z1 = baseZ + scale;
            }
            case WEST, EAST -> {
                x1 = baseX + scale;
                y1 = baseY + width * scale;
                z1 = baseZ + length * scale;
            }
            default -> throw new IllegalStateException("Unexpected value: " + dir);
        }

        float[] positions;
        float[] normals = new float[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()};

        switch (dir) {
            case DOWN -> {
                float xx0 = lerp(baseX, x1, u0);
                float xx1 = lerp(baseX, x1, u1);
                float zz0 = lerp(baseZ, z1, v0);
                float zz1 = lerp(baseZ, z1, v1);
                float yy = baseY;
                positions = new float[]{
                    xx0, yy, zz1,
                    xx0, yy, zz0,
                    xx1, yy, zz0,
                    xx1, yy, zz1
                };
            }
            case UP -> {
                float xx0 = lerp(baseX, x1, u0);
                float xx1 = lerp(baseX, x1, u1);
                float zz0 = lerp(baseZ, z1, v0);
                float zz1 = lerp(baseZ, z1, v1);
                float yy = baseY + scale;
                positions = new float[]{
                    xx0, yy, zz0,
                    xx0, yy, zz1,
                    xx1, yy, zz1,
                    xx1, yy, zz0
                };
            }
            case NORTH -> {
                float xx0 = lerp(baseX, x1, u0);
                float xx1 = lerp(baseX, x1, u1);
                float yy0 = lerp(baseY, y1, v0);
                float yy1 = lerp(baseY, y1, v1);
                float zz = baseZ;
                positions = new float[]{
                    xx1, yy1, zz,
                    xx1, yy0, zz,
                    xx0, yy0, zz,
                    xx0, yy1, zz
                };
            }
            case SOUTH -> {
                float xx0 = lerp(baseX, x1, u0);
                float xx1 = lerp(baseX, x1, u1);
                float yy0 = lerp(baseY, y1, v0);
                float yy1 = lerp(baseY, y1, v1);
                float zz = baseZ + scale;
                positions = new float[]{
                    xx0, yy1, zz,
                    xx0, yy0, zz,
                    xx1, yy0, zz,
                    xx1, yy1, zz
                };
            }
            case WEST -> {
                float zz0 = lerp(baseZ, z1, u0);
                float zz1 = lerp(baseZ, z1, u1);
                float yy0 = lerp(baseY, y1, v0);
                float yy1 = lerp(baseY, y1, v1);
                float xx = baseX;
                positions = new float[]{
                    xx, yy1, zz0,
                    xx, yy0, zz0,
                    xx, yy0, zz1,
                    xx, yy1, zz1
                };
            }
            case EAST -> {
                float zz0 = lerp(baseZ, z1, u0);
                float zz1 = lerp(baseZ, z1, u1);
                float yy0 = lerp(baseY, y1, v0);
                float yy1 = lerp(baseY, y1, v1);
                float xx = baseX + scale;
                positions = new float[]{
                    xx, yy1, zz1,
                    xx, yy0, zz1,
                    xx, yy0, zz0,
                    xx, yy1, zz0
                };
            }
            default -> positions = new float[12];
        }

        float[] uvTemplate = FACE_UV_TEMPLATE[dir.get3DDataValue()];
        float[] uvs = new float[]{
            lerp(uvU0, uvU1, uvTemplate[0]), lerp(uvV0, uvV1, uvTemplate[1]),
            lerp(uvU0, uvU1, uvTemplate[2]), lerp(uvV0, uvV1, uvTemplate[3]),
            lerp(uvU0, uvU1, uvTemplate[4]), lerp(uvV0, uvV1, uvTemplate[5]),
            lerp(uvU0, uvU1, uvTemplate[6]), lerp(uvV0, uvV1, uvTemplate[7])
        };

        sink.addQuad(
            payload.key.materialKey,
            payload.key.spriteName,
            null,
            positions,
            uvs,
            null,
            normals,
            payload.baseColors,
            false
        );

        if (payload.key.overlaySprite != null) {
            float eps = 0.001f * scale;
            float[] overlayPos = positions.clone();
            for (int i = 0; i < 4; i++) {
                int p = i * 3;
                overlayPos[p] += normals[0] * eps;
                overlayPos[p + 1] += normals[1] * eps;
                overlayPos[p + 2] += normals[2] * eps;
            }
            sink.addQuad(
                payload.key.materialKey,
                payload.key.overlaySprite,
                null,
                overlayPos,
                uvs,
                null,
                normals,
                payload.overlayColors,
                false
            );
        }
        stats.faces++;
    }

    private static final class GreedyFaceKey {
        private final int blockId;
        private final String materialKey;
        private final String spriteName;
        private final String overlaySprite;
        private final int baseTintColor;
        private final int overlayTintColor;

        private GreedyFaceKey(int blockId, String materialKey, String spriteName, String overlaySprite, int baseTintColor, int overlayTintColor) {
            this.blockId = blockId;
            this.materialKey = materialKey;
            this.spriteName = spriteName;
            this.overlaySprite = overlaySprite;
            this.baseTintColor = baseTintColor;
            this.overlayTintColor = overlayTintColor;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GreedyFaceKey key)) return false;
            if (blockId != key.blockId) return false;
            if (baseTintColor != key.baseTintColor) return false;
            if (overlayTintColor != key.overlayTintColor) return false;
            if (!materialKey.equals(key.materialKey)) return false;
            if (!spriteName.equals(key.spriteName)) return false;
            return overlaySprite != null ? overlaySprite.equals(key.overlaySprite) : key.overlaySprite == null;
        }

        @Override
        public int hashCode() {
            int result = blockId;
            result = 31 * result + materialKey.hashCode();
            result = 31 * result + spriteName.hashCode();
            result = 31 * result + (overlaySprite != null ? overlaySprite.hashCode() : 0);
            result = 31 * result + baseTintColor;
            result = 31 * result + overlayTintColor;
            return result;
        }
    }

    private static final class GreedyFacePayload {
        private final GreedyFaceKey key;
        private final float[] baseColors;
        private final float[] overlayColors;
        private final float u0;
        private final float u1;
        private final float v0;
        private final float v1;
        private final float uvU0;
        private final float uvU1;
        private final float uvV0;
        private final float uvV1;

        private GreedyFacePayload(GreedyFaceKey key,
                                  float[] baseColors,
                                  float[] overlayColors,
                                  float u0,
                                  float u1,
                                  float v0,
                                  float v1,
                                  float uvU0,
                                  float uvU1,
                                  float uvV0,
                                  float uvV1) {
            this.key = key;
            this.baseColors = baseColors;
            this.overlayColors = overlayColors;
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
            this.uvU0 = uvU0;
            this.uvU1 = uvU1;
            this.uvV0 = uvV0;
            this.uvV1 = uvV1;
        }
    }

    private int shouldRenderFace(LodBlockGetter blockGetter,
                                 BlockPos.MutableBlockPos pos,
                                 BlockPos.MutableBlockPos neighborPos,
                                 Direction dir,
                                 BlockState state,
                                 int level,
                                 int chunkX,
                                 int chunkY,
                                 int chunkZ,
                                 int localX,
                                 int localY,
                                 int localZ) {
        neighborPos.setWithOffset(pos, dir);
        boolean visible = Block.shouldRenderFace(state, blockGetter, pos, dir, neighborPos);

        if (level > 0) {
            boolean isBoundary = switch (dir) {
                case DOWN -> localY == 0;
                case UP -> localY == 31;
                case NORTH -> localZ == 0;
                case SOUTH -> localZ == 31;
                case WEST -> localX == 0;
                case EAST -> localX == 31;
            };

            if (isBoundary) {
                int neighborChunkX = chunkX + dir.getStepX();
                int neighborChunkY = chunkY + dir.getStepY();
                int neighborChunkZ = chunkZ + dir.getStepZ();
                long neighborSectionId = WorldEngine.getWorldSectionId(level, neighborChunkX, neighborChunkY, neighborChunkZ);
                boolean neighborWillBeMeshed = sectionsToMesh != null && sectionsToMesh.contains(neighborSectionId);

                if (neighborWillBeMeshed) {
                    return visible ? FACE_DECISION_FULL : FACE_DECISION_SKIP;
                }

                boolean fineSnapshotExists = hasFineSnapshot(dir, localX, localY, localZ);
                if (fineSnapshotExists) {
                    int mask = computeFineOcclusionMask(localX, localY, localZ, dir);
                    boolean fullyOccluded = mask == 0xF;
                    if (visible) {
                        if (fullyOccluded) {
                            return FACE_DECISION_SKIP;
                        }
                        if (mask == 0) {
                            return FACE_DECISION_FULL;
                        }
                        return mask;
                    } else {
                        if (fullyOccluded) {
                            return FACE_DECISION_SKIP;
                        }
                        if (mask == 0) {
                            return FACE_DECISION_FULL;
                        }
                        return mask;
                    }
                } else {
                    return FACE_DECISION_SKIP;
                }
            }
        }

        return visible ? FACE_DECISION_FULL : FACE_DECISION_SKIP;
    }

    private void emitFluidFaces(LodBlockGetter blockGetter,
                                BlockPos.MutableBlockPos pos,
                                BlockPos.MutableBlockPos neighborPos,
                                long[] data,
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
        buildFluidMasks(data);
        int originX = chunkX * 32;
        int originY = chunkY * 32;
        int originZ = chunkZ * 32;

        // X 轴界面（东西面）
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int mask = fluidMasks[y * 32 + z];
                if (mask == 0) continue;
                // East：当前有流体且右侧无流体
                int eastMask = mask & ~(mask << 1);
                while (eastMask != 0) {
                    int x = Integer.numberOfTrailingZeros(eastMask);
                    eastMask &= eastMask - 1;
                    if (x == 31 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.EAST)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.EAST, scale, stats);
                }
                // West：当前有流体且左侧无流体
                int westMask = mask & ~(mask >>> 1);
                while (westMask != 0) {
                    int x = Integer.numberOfTrailingZeros(westMask);
                    westMask &= westMask - 1;
                    if (x == 0 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.WEST)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.WEST, scale, stats);
                }
            }
        }

        // Y 轴界面（上下）
        for (int y = 0; y < 32; y++) {
            int prevY = y == 0 ? -1 : y - 1;
            int nextY = y == 31 ? -1 : y + 1;
            for (int z = 0; z < 32; z++) {
                int currMask = fluidMasks[y * 32 + z];
                if (currMask == 0) continue;
                int prevMask = prevY >= 0 ? fluidMasks[prevY * 32 + z] : 0;
                int nextMask = nextY >= 0 ? fluidMasks[nextY * 32 + z] : 0;

                // Up：当前有流体且上层无流体
                int upMask = currMask & ~nextMask;
                while (upMask != 0) {
                    int x = Integer.numberOfTrailingZeros(upMask);
                    upMask &= upMask - 1;
                    if (y == 31 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.UP)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.UP, scale, stats);
                }
                // Down：当前有流体且下层无流体
                int downMask = currMask & ~prevMask;
                while (downMask != 0) {
                    int x = Integer.numberOfTrailingZeros(downMask);
                    downMask &= downMask - 1;
                    if (y == 0 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.DOWN)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.DOWN, scale, stats);
                }
            }
        }

        // Z 轴界面（南北）
        for (int z = 0; z < 32; z++) {
            int prevZ = z == 0 ? -1 : z - 1;
            int nextZ = z == 31 ? -1 : z + 1;
            for (int y = 0; y < 32; y++) {
                int currMask = fluidMasks[y * 32 + z];
                if (currMask == 0) continue;
                int prevMask = prevZ >= 0 ? fluidMasks[y * 32 + prevZ] : 0;
                int nextMask = nextZ >= 0 ? fluidMasks[y * 32 + nextZ] : 0;

                // South：当前有流体且 +Z 无流体
                int southMask = currMask & ~nextMask;
                while (southMask != 0) {
                    int x = Integer.numberOfTrailingZeros(southMask);
                    southMask &= southMask - 1;
                    if (z == 31 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.SOUTH)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.SOUTH, scale, stats);
                }
                // North：当前有流体且 -Z 无流体
                int northMask = currMask & ~prevMask;
                while (northMask != 0) {
                    int x = Integer.numberOfTrailingZeros(northMask);
                    northMask &= northMask - 1;
                    if (z == 0 && hasFluidNeighbor(negX, posX, negY, posY, negZ, posZ, x, y, z, Direction.NORTH)) {
                        continue;
                    }
                    emitFluidFaceIfVisible(blockGetter, pos, neighborPos, data, negX, posX, negY, posY, negZ, posZ,
                        originX, originY, originZ, chunkX, chunkY, chunkZ,
                        x, y, z, Direction.NORTH, scale, stats);
                }
            }
        }
    }

    private void buildFluidMasks(long[] data) {
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                int mask = 0;
                for (int x = 0; x < 32; x++) {
                    int idx = WorldSection.getIndex(x, y, z);
                    long id = data[idx];
                    if (Mapper.isAir(id)) continue;
                    int blockId = Mapper.getBlockId(id);
                    if (blockHasFluid(blockId)) {
                        mask |= (1 << x);
                    }
                }
                fluidMasks[y * 32 + z] = mask;
            }
        }
    }

    private void emitFluidFaceIfVisible(LodBlockGetter blockGetter,
                                        BlockPos.MutableBlockPos pos,
                                        BlockPos.MutableBlockPos neighborPos,
                                        long[] data,
                                        long[] negX,
                                        long[] posX,
                                        long[] negY,
                                        long[] posY,
                                        long[] negZ,
                                        long[] posZ,
                                        int originX,
                                        int originY,
                                        int originZ,
                                        int chunkX,
                                        int chunkY,
                                        int chunkZ,
                                        int x,
                                        int y,
                                        int z,
                                        Direction dir,
                                        int scale,
                                        MeshStats stats) {
        // 跨 section 边界先查邻居流体，避免重复面
        if (!isBoundaryFaceVisible(negX, posX, negY, posY, negZ, posZ, x, y, z, dir)) {
            return;
        }

        int idx = WorldSection.getIndex(x, y, z);
        long id = data[idx];
        if (Mapper.isAir(id)) {
            return;
        }
        int blockId = Mapper.getBlockId(id);
        BlockState state = mapper.getBlockStateFromBlockId(blockId);
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty()) {
            return;
        }
        BlockState fluidBlock = state.getBlock() instanceof LiquidBlock
            ? state
            : fluidState.createLegacyBlock();
        int fluidBlockId = state.getBlock() instanceof LiquidBlock
            ? blockId
            : resolveFluidBlockId(blockId, fluidBlock);
        if (fluidBlockId <= 0) {
            return;
        }
        int biomeId = Mapper.getBiomeId(id);
        pos.set(originX + x, originY + y, originZ + z);
        if (shouldRenderFluidFace(blockGetter, pos, neighborPos, dir, fluidBlock)) {
            emitFace(fluidBlock, fluidBlockId, biomeId, chunkX, chunkY, chunkZ, x, y, z, dir, scale, stats);
        }
    }

    private boolean hasFluidAt(long[] data, int x, int y, int z) {
        int idx = WorldSection.getIndex(x, y, z);
        long id = data[idx];
        if (Mapper.isAir(id)) {
            return false;
        }
        return blockHasFluid(Mapper.getBlockId(id));
    }

    private boolean hasFluidNeighbor(long[] negX, long[] posX, long[] negY, long[] posY, long[] negZ, long[] posZ,
                                     int x, int y, int z, Direction dir) {
        int nx = x + dir.getStepX();
        int ny = y + dir.getStepY();
        int nz = z + dir.getStepZ();
        if (nx < 0) {
            return negX != null && hasFluidAt(negX, 31, y, z);
        }
        if (nx >= 32) {
            return posX != null && hasFluidAt(posX, 0, y, z);
        }
        if (ny < 0) {
            return negY != null && hasFluidAt(negY, x, 31, z);
        }
        if (ny >= 32) {
            return posY != null && hasFluidAt(posY, x, 0, z);
        }
        if (nz < 0) {
            return negZ != null && hasFluidAt(negZ, x, y, 31);
        }
        if (nz >= 32) {
            return posZ != null && hasFluidAt(posZ, x, y, 0);
        }
        return false;
    }

    private boolean isBoundaryFaceVisible(long[] negX, long[] posX, long[] negY, long[] posY, long[] negZ, long[] posZ,
                                          int x, int y, int z, Direction dir) {
        // 内部界面在掩码阶段已处理；这里只处理跨 section 边界的流体遮挡
        int nx = x + dir.getStepX();
        int ny = y + dir.getStepY();
        int nz = z + dir.getStepZ();
        long[] neighbor = null;
        int lx = nx;
        int ly = ny;
        int lz = nz;
        if (nx < 0) {
            neighbor = negX;
            lx = 31;
        } else if (nx >= 32) {
            neighbor = posX;
            lx = 0;
        } else if (ny < 0) {
            neighbor = negY;
            ly = 31;
        } else if (ny >= 32) {
            neighbor = posY;
            ly = 0;
        } else if (nz < 0) {
            neighbor = negZ;
            lz = 31;
        } else if (nz >= 32) {
            neighbor = posZ;
            lz = 0;
        }
        if (neighbor == null) {
            return true; // 缺邻居，保守生成
        }
        long nid = neighbor[WorldSection.getIndex(lx, ly, lz)];
        if (Mapper.isAir(nid)) {
            return true;
        }
        return !blockHasFluid(Mapper.getBlockId(nid));
    }

    private boolean shouldRenderFluidFace(LodBlockGetter blockGetter,
                                          BlockPos.MutableBlockPos pos,
                                          BlockPos.MutableBlockPos neighborPos,
                                          Direction dir,
                                          BlockState fluidBlock) {
        neighborPos.setWithOffset(pos, dir);
        BlockState neighborState = blockGetter.getBlockState(neighborPos);

        // 邻居含流体（纯流体或 waterlogged）直接剔除
        if (!neighborState.getFluidState().isEmpty()) {
            return false;
        }

        // 邻居为可遮挡的实心块，也不渲染流体面，避免水下与方块接触处重复
        if (neighborState.canOcclude()) {
            return false;
        }

        return true;
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
        emitFace(state, blockId, biomeId, chunkX, chunkY, chunkZ, localX, localY, localZ, dir, scale, FACE_DECISION_FULL, stats);
    }

    private void emitFace(BlockState state, int blockId, int biomeId, int chunkX, int chunkY, int chunkZ, int localX, int localY, int localZ, Direction dir, int scale, int fineOcclusionMask, MeshStats stats) {
        if (state.getRenderShape() == RenderShape.INVISIBLE && !(state.getBlock() instanceof LiquidBlock)) {
            return;
        }
        String materialKey;
        String spriteName;
        LodFaceMeta faceMeta = null;
        String overlaySprite = null;
        boolean disableLodTexture = state.getBlock() instanceof LiquidBlock;
        if (FORCE_LOD_MATERIAL) {
            materialKey = LOD_MATERIAL_KEY;
            spriteName = LOD_SPRITE_KEY;
        } else {
            if (disableLodTexture) {
                spriteName = resolveSpriteKeyVanilla(state, blockId, dir);
            } else {
                spriteName = resolveSpriteKey(state, blockId, dir);
                if (hasLodTextures(blockId) && lodTextureProvider instanceof LodFaceProvider faceProvider) {
                    faceMeta = faceProvider.getFaceMeta(blockId, dir);
                    if (lodTextureProvider instanceof LodOverlayProvider overlayProvider) {
                        overlaySprite = overlayProvider.getOverlaySpriteKey(blockId, dir);
                    }
                }
            }
            materialKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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

        int baseTintColor = -1;
        int overlayTintColor = -1;

        if (overlaySprite != null) {
            boolean isGrassBlock = state.getBlock() == Blocks.GRASS_BLOCK;
            boolean isOverlayType = isOverlaySprite(overlaySprite);

            if ((isGrassBlock && dir.getAxis().isHorizontal()) || isOverlayType) {
                baseTintColor = -1;
                overlayTintColor = tintColor;
            } else {
                baseTintColor = tintColor;
                overlayTintColor = -1;
            }
        } else {
            baseTintColor = tintColor;
        }

        float[] baseColors = WHITE_COLORS;
        float[] overlayColors = WHITE_COLORS;
        String combinedSprite = null;

        if (overlaySprite != null && exportContext != null) {
            combinedSprite = buildLayeredTintedSprite(exportContext, spriteName, baseTintColor, overlaySprite, overlayTintColor);
            if (combinedSprite != null) {
                spriteName = combinedSprite;
                overlaySprite = null;
                baseColors = WHITE_COLORS;
            } else {
                baseColors = resolveColors(baseTintColor, baseTintColor != -1);
                overlayColors = resolveColors(overlayTintColor, overlayTintColor != -1);
            }
        } else {
            if (baseTintColor != -1) {
                baseColors = resolveColors(baseTintColor, true);
            } else {
                baseColors = WHITE_COLORS;
            }
        }

        if (exportContext != null) {
            if (combinedSprite != null) {
                com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, 0xFFFFFF);
            } else {
                boolean isBaseTinted = baseTintColor != -1;
                boolean isOverlay = overlaySprite != null && overlayTintColor != -1;
                boolean isBaseSpriteOverlay = isOverlaySprite(spriteName);

                if (isBaseTinted || isBaseSpriteOverlay) {
                    int colorToRegister = (overlaySprite != null) ? 0xFFFFFF : (isBaseTinted ? baseTintColor : 0xFFFFFF);
                    com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, spriteName, colorToRegister);
                }

                if (overlaySprite != null) {
                    int overlayRegColor = overlayTintColor != -1 ? overlayTintColor : 0xFFFFFF;
                    com.voxelbridge.export.texture.TextureAtlasManager.registerTint(exportContext, overlaySprite, overlayRegColor);
                }
            }
        }

        float baseX = (float) ((chunkX * 32 + localX) * scale + offsetX);
        float baseY = (float) ((chunkY * 32 + localY) * scale + offsetY);
        float baseZ = (float) ((chunkZ * 32 + localZ) * scale + offsetZ);

        if (faceMeta != null && faceMeta.empty) {
            return;
        }

        float u0 = 0f;
        float u1 = 1f;
        float v0 = 0f;
        float v1 = 1f;
        float depthOffset = 0f;

        float uvU0 = 0f;
        float uvU1 = 1f;
        float uvV0 = 0f;
        float uvV1 = 1f;

        boolean individualMode = isIndividualAtlasMode();

        if (faceMeta != null) {
            u0 = faceMeta.minX / 16.0f;
            u1 = (faceMeta.maxX + 1) / 16.0f;
            v0 = faceMeta.minY / 16.0f;
            v1 = (faceMeta.maxY + 1) / 16.0f;

            uvU0 = u0;
            uvU1 = u1;
            uvV0 = 1.0f - v1;
            uvV1 = 1.0f - v0;

            depthOffset = clamp01(faceMeta.depth);
            if ((dir.get3DDataValue() & 1) == 1) {
                depthOffset = 1.0f - depthOffset;
            }
        }

        if (individualMode) {
            String cropped = ensureCroppedSprite(exportContext, spriteName, faceMeta);
            if (cropped != null) {
                spriteName = cropped;
                uvU0 = 0f;
                uvU1 = 1f;
                uvV0 = 0f;
                uvV1 = 1f;
            }
            if (overlaySprite != null) {
                String croppedOverlay = ensureCroppedSprite(exportContext, overlaySprite, faceMeta);
                if (croppedOverlay != null) {
                    overlaySprite = croppedOverlay;
                }
            }
            materialKey = overlaySprite != null ? (spriteName + "+ov+" + overlaySprite) : spriteName;
        }

        int occlusionMask = (fineOcclusionMask >= 0 && fineOcclusionMask < FACE_DECISION_FULL) ? fineOcclusionMask : 0;
        if (occlusionMask == 0xF) {
            return;
        }
        if (occlusionMask != 0) {
            emitCroppedFace(materialKey, spriteName, overlaySprite, baseColors, overlayColors, faceMeta,
                dir, scale, baseX, baseY, baseZ, u0, u1, v0, v1, uvU0, uvU1, uvV0, uvV1, depthOffset, occlusionMask, stats);
            return;
        }

        emitFaceSection(materialKey, spriteName, overlaySprite, baseColors, overlayColors, faceMeta,
            dir, scale, baseX, baseY, baseZ, u0, u1, v0, v1, uvU0, uvU1, uvV0, uvV1, depthOffset, stats);
    }

    private void emitCroppedFace(String materialKey,
                                 String spriteName,
                                 String overlaySprite,
                                 float[] baseColors,
                                 float[] overlayColors,
                                 LodFaceMeta faceMeta,
                                 Direction dir,
                                 int scale,
                                 float baseX,
                                 float baseY,
                                 float baseZ,
                                 float u0,
                                 float u1,
                                 float v0,
                                 float v1,
                                 float uvU0,
                                 float uvU1,
                                 float uvV0,
                                 float uvV1,
                                 float depthOffset,
                                 int occlusionMask,
                                 MeshStats stats) {
        float midU = lerp(u0, u1, 0.5f);
        float midV = lerp(v0, v1, 0.5f);
        float midUvU = lerp(uvU0, uvU1, 0.5f);
        float midUvV = lerp(uvV0, uvV1, 0.5f);

        for (int vIdx = 0; vIdx < 2; vIdx++) {
            float subV0 = (vIdx == 0) ? v0 : midV;
            float subV1 = (vIdx == 0) ? midV : v1;
            float subUvV0 = (vIdx == 0) ? uvV0 : midUvV;
            float subUvV1 = (vIdx == 0) ? midUvV : uvV1;
            for (int uIdx = 0; uIdx < 2; uIdx++) {
                int bit = (vIdx << 1) | uIdx;
                if ((occlusionMask & (1 << bit)) != 0) {
                    continue;
                }
                float subU0 = (uIdx == 0) ? u0 : midU;
                float subU1 = (uIdx == 0) ? midU : u1;
                float subUvU0 = (uIdx == 0) ? uvU0 : midUvU;
                float subUvU1 = (uIdx == 0) ? midUvU : uvU1;

                emitFaceSection(materialKey, spriteName, overlaySprite, baseColors, overlayColors, faceMeta,
                    dir, scale, baseX, baseY, baseZ, subU0, subU1, subV0, subV1, subUvU0, subUvU1, subUvV0, subUvV1, depthOffset, stats);
            }
        }
    }

    private void emitFaceSection(String materialKey,
                                 String spriteName,
                                 String overlaySprite,
                                 float[] baseColors,
                                 float[] overlayColors,
                                 LodFaceMeta faceMeta,
                                 Direction dir,
                                 int scale,
                                 float baseX,
                                 float baseY,
                                 float baseZ,
                                 float u0,
                                 float u1,
                                 float v0,
                                 float v1,
                                 float uvU0,
                                 float uvU1,
                                 float uvV0,
                                 float uvV1,
                                 float depthOffset,
                                 MeshStats stats) {
        float[] positions;
        float[] normals = new float[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()};
        float x0 = baseX, x1 = baseX + scale;
        float y0 = baseY, y1 = baseY + scale;
        float z0 = baseZ, z1 = baseZ + scale;

        switch (dir) {
            case DOWN -> {
                float xx0 = lerp(x0, x1, u0);
                float xx1 = lerp(x0, x1, u1);
                float zz0 = lerp(z0, z1, v0);
                float zz1 = lerp(z0, z1, v1);
                float yy = faceMeta != null ? (baseY + depthOffset * scale) : y0;
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
                positions = new float[]{
                    xx0, yy1, zz,
                    xx0, yy0, zz,
                    xx1, yy0, zz,
                    xx1, yy1, zz
                };
            }
            case WEST -> {
                float gu0 = u0;
                float gu1 = u1;
                if (faceMeta != null) {
                    gu0 = 1.0f - u1;
                    gu1 = 1.0f - u0;
                }
                float zz0 = lerp(z0, z1, gu0);
                float zz1 = lerp(z0, z1, gu1);
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
                float gu0 = u0;
                float gu1 = u1;
                if (faceMeta != null) {
                    gu0 = 1.0f - u1;
                    gu1 = 1.0f - u0;
                }
                float zz0 = lerp(z0, z1, gu0);
                float zz1 = lerp(z0, z1, gu1);
                float yy0 = lerp(y0, y1, v0);
                float yy1 = lerp(y0, y1, v1);
                float xx = faceMeta != null ? (baseX + depthOffset * scale) : x1;
                positions = new float[]{
                    xx, yy1, zz1,
                    xx, yy0, zz1,
                    xx, yy0, zz0,
                    xx, yy1, zz0
                };
            }
            default -> positions = new float[12];
        }

        float[] uvTemplate = FACE_UV_TEMPLATE[dir.get3DDataValue()];
        float[] uvs = new float[]{
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

            String matKey = materialKey;
            if (exportContext != null && isIndividualAtlasMode()) {
                matKey = spriteKey;
            }

            sink.addQuad(
                matKey,
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

    // 不走 LOD 贴图的原版 sprite 解析，用于流体等特殊情况
    private String resolveSpriteKeyVanilla(BlockState state, int blockId, Direction dir) {
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
            int[] baseTintIndices = new int[6];
            for (int i = 0; i < 6; i++) {
                tintIndices[i] = -1;
                baseTintIndices[i] = -1;
            }
            for (Direction dir : Direction.values()) {
                RandomSource rand = RandomSource.create(SPRITE_RANDOM_SEED);
                List<BakedQuad> quads = model.getQuads(state, dir, rand, ModelData.EMPTY, null);
                if (!quads.isEmpty()) {
                    TextureAtlasSprite sprite = quads.get(0).getSprite();
                    faces[dir.ordinal()] = sprite.contents().name().toString();
                    tintIndices[dir.ordinal()] = pickTintIndex(quads);
                    baseTintIndices[dir.ordinal()] = quads.get(0).getTintIndex();
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
                    int baseTintIdx = general.get(0).getTintIndex();
                    for (Direction dir : Direction.values()) {
                        int idx = dir.ordinal();
                        if (faces[idx] == null || faces[idx].equals(fallback)) {
                            faces[idx] = spriteName;
                            tintIndices[idx] = tintIdx;
                            baseTintIndices[idx] = baseTintIdx;
                        }
                    }
                }
            }
            return new FaceSpriteSet(fallback, faces, tintIndices, baseTintIndices);
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

    private int resolveBaseTintIndex(BlockState state, int blockId, Direction dir) {
        FaceSpriteSet cached = faceSpriteCache.get(blockId);
        if (cached == null) {
            cached = buildFaceSprites(state);
            faceSpriteCache.put(blockId, cached);
        }
        return cached.baseTintIndices[dir.ordinal()];
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

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Individual 模式下为裁剪区域生成独立贴图 key，保证每个材质只对应一种 UV 引用。
     */
    private String ensureCroppedSprite(ExportContext ctx, String baseKey, LodFaceMeta meta) {
        if (ctx == null || baseKey == null || meta == null) {
            return null;
        }
        String croppedKey = baseKey + "#crop_" + meta.minX + "_" + meta.maxX + "_" + meta.minY + "_" + meta.maxY;
        if (ctx.getCachedSpriteImage(croppedKey) != null) {
            return croppedKey;
        }

        BufferedImage baseImg = loadSpriteImage(ctx, baseKey);
        if (baseImg == null) {
            return null;
        }

        int w = baseImg.getWidth();
        int h = baseImg.getHeight();
        int minX = clamp(meta.minX, 0, w - 1);
        int maxX = clamp(meta.maxX, 0, w - 1);
        int minY = clamp(meta.minY, 0, h - 1);
        int maxY = clamp(meta.maxY, 0, h - 1);
        if (minX > maxX || minY > maxY) {
            return null;
        }

        // meta 的 Y 以底部为 0，需要转换为图片坐标（顶部为 0）
        int srcX = minX;
        int srcY = h - 1 - maxY;
        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        if (srcY < 0 || srcY + ch > h) {
            return null;
        }

        BufferedImage cropped = baseImg.getSubimage(srcX, srcY, cw, ch);
        ctx.cacheSpriteImage(croppedKey, cropped);
        return croppedKey;
    }

    private BufferedImage loadSpriteImage(ExportContext ctx, String spriteKey) {
        BufferedImage cached = ctx.getCachedSpriteImage(spriteKey);
        if (cached != null) {
            return cached;
        }
        try {
            ResourceLocation loc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            if (loc == null) {
                return null;
            }
            BufferedImage img = TextureLoader.readTexture(loc, ExportRuntimeConfig.isAnimationEnabled());
            if (img != null) {
                ctx.getTextureRepository().put(loc, spriteKey, img);
            }
            return img;
        } catch (Exception e) {
            return null;
        }
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

    private static String buildLayeredTintedSprite(ExportContext ctx, String baseKey, int baseTint, String overlayKey, int overlayTint) {
        if (ctx == null || baseKey == null || overlayKey == null) {
            return null;
        }
        int baseColor = baseTint == -1 ? 0xFFFFFF : (baseTint & 0xFFFFFF);
        int overlayColor = overlayTint == -1 ? 0xFFFFFF : (overlayTint & 0xFFFFFF);
        String combinedKey = baseKey + "|layer|" + String.format("%06X", baseColor) + "|overlay|" + overlayKey + "|overlayTint|" + String.format("%06X", overlayColor);
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

        int brMul = (baseColor >> 16) & 0xFF;
        int bgMul = (baseColor >> 8) & 0xFF;
        int bbMul = baseColor & 0xFF;

        int orMul = (overlayColor >> 16) & 0xFF;
        int ogMul = (overlayColor >> 8) & 0xFF;
        int obMul = overlayColor & 0xFF;

        for (int i = 0; i < basePixels.length; i++) {
            int baseArgb = basePixels[i];
            int ba = (baseArgb >>> 24) & 0xFF;
            int br = (baseArgb >>> 16) & 0xFF;
            int bg = (baseArgb >>> 8) & 0xFF;
            int bb = baseArgb & 0xFF;

            br = (br * brMul) / 255;
            bg = (bg * bgMul) / 255;
            bb = (bb * bbMul) / 255;

            int overArgb = overlayPixels[i];
            int oa = (overArgb >>> 24) & 0xFF;
            int or = (overArgb >>> 16) & 0xFF;
            int og = (overArgb >>> 8) & 0xFF;
            int ob = overArgb & 0xFF;

            or = (or * orMul) / 255;
            og = (og * ogMul) / 255;
            ob = (ob * obMul) / 255;

            if (oa == 0) {
                outPixels[i] = (ba << 24) | (br << 16) | (bg << 8) | bb;
                continue;
            }

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

    private static String buildTintedSprite(ExportContext ctx, String baseKey, int tintColor) {
        if (ctx == null || baseKey == null || tintColor == -1) {
            return null;
        }
        int color = tintColor & 0xFFFFFF;
        String tintedKey = baseKey + "|tint|" + String.format("%06X", color);
        if (ctx.getCachedSpriteImage(tintedKey) != null) {
            return tintedKey;
        }
        BufferedImage base = ctx.getCachedSpriteImage(baseKey);
        if (base == null) {
            return null;
        }
        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = base.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[pixels.length];

        int rMul = (color >> 16) & 0xFF;
        int gMul = (color >> 8) & 0xFF;
        int bMul = color & 0xFF;

        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;

            r = (r * rMul) / 255;
            g = (g * gMul) / 255;
            b = (b * bMul) / 255;

            outPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        ctx.cacheSpriteImage(tintedKey, out);
        return tintedKey;
    }

    // Removed emitDebugQuad

}

