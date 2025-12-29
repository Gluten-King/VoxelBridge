package com.voxelbridge.voxy.mesh;

import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.common.world.WorldSection;
import com.voxelbridge.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.BitSet;

public class VoxelMesher {
    private final WorldEngine worldEngine;
    private final Mapper mapper;
    private final SceneSink sink;
    private final RandomSource random = RandomSource.create();
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private static final boolean FORCE_LOD_MATERIAL = true;
    private static final String LOD_MATERIAL_KEY = "lod";
    private static final String LOD_SPRITE_KEY = "minecraft:block/white_wool";

    private static final class MeshStats {
        int nonAirBlocks;
        int faces;
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink) {
        this(worldEngine, sink, 0, 0, 0);
    }

    public VoxelMesher(WorldEngine worldEngine, SceneSink sink, double offsetX, double offsetY, double offsetZ) {
        this.worldEngine = worldEngine;
        this.mapper = worldEngine.getMapper();
        this.sink = sink;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    public void meshChunk(int chunkX, int chunkY, int chunkZ, int level) {
        int scale = 1 << level;

        WorldSection section = worldEngine.acquire(level, chunkX, chunkY, chunkZ);
        if (section == null) {
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] mesh skip missing section lvl=%d pos=[%d,%d,%d]", level, chunkX, chunkY, chunkZ));
            return;
        }

        try {
            long[] data = section.copyData();
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
                        BlockState state = mapper.getBlockStateFromBlockId(blockId);
                        
                        // Simple Culling: Check neighbors
                        // TODO: Check neighbors across section boundaries (requires acquiring neighbor sections)
                        // For now, we only cull internal to the section. Boundary faces will be generated.
                        
                        if (shouldRenderFace(data, x, y, z, Direction.UP)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.UP, scale, stats);
                        }
                        if (shouldRenderFace(data, x, y, z, Direction.DOWN)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.DOWN, scale, stats);
                        }
                        if (shouldRenderFace(data, x, y, z, Direction.NORTH)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.NORTH, scale, stats);
                        }
                        if (shouldRenderFace(data, x, y, z, Direction.SOUTH)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.SOUTH, scale, stats);
                        }
                        if (shouldRenderFace(data, x, y, z, Direction.WEST)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.WEST, scale, stats);
                        }
                        if (shouldRenderFace(data, x, y, z, Direction.EAST)) {
                            emitFace(state, chunkX, chunkY, chunkZ, x, y, z, Direction.EAST, scale, stats);
                        }
                    }
                }
            }
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] mesh section lvl=%d pos=[%d,%d,%d] nonAir=%d faces=%d",
                level, chunkX, chunkY, chunkZ, stats.nonAirBlocks, stats.faces));
        } finally {
            section.release();
        }
    }

    private boolean shouldRenderFace(long[] data, int x, int y, int z, Direction dir) {
        int nx = x + dir.getStepX();
        int ny = y + dir.getStepY();
        int nz = z + dir.getStepZ();

        // If out of bounds of this section, render it (conservative approach)
        // Optimally, we would check the neighbor section.
        if (nx < 0 || nx >= 32 || ny < 0 || ny >= 32 || nz < 0 || nz >= 32) {
            return true;
        }

        int neighborIdx = WorldSection.getIndex(nx, ny, nz);
        long neighborId = data[neighborIdx];
        
        // If neighbor is air, we must render
        if (Mapper.isAir(neighborId)) return true;

        // If neighbor is not air, usually we cull. 
        // But transparent blocks (glass) generally don't cull other blocks, only themselves if same type.
        // For simplicity in this v1, we cull if neighbor is solid.
        // TODO: use BlockState.isOccluding or skipRendering checks
        return false;
    }

    private void emitFace(BlockState state, int chunkX, int chunkY, int chunkZ, int localX, int localY, int localZ, Direction dir, int scale, MeshStats stats) {
        String materialKey;
        String spriteName;
        if (FORCE_LOD_MATERIAL) {
            materialKey = LOD_MATERIAL_KEY;
            spriteName = LOD_SPRITE_KEY;
        } else {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            var sprite = model.getParticleIcon();
            spriteName = sprite.contents().name().toString();
            materialKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }

        // Generate geometry for a standard unit face
        float[] positions = new float[12];
        float[] uvs = new float[8];
        float[] normals = new float[]{dir.getStepX(), dir.getStepY(), dir.getStepZ()};
        float[] colors = new float[]{1,1,1,1, 1,1,1,1, 1,1,1,1, 1,1,1,1}; // White
        
        // Basic Unit Cube Geometry
        float baseX = (float) ((chunkX * 32 + localX) * scale + offsetX);
        float baseY = (float) ((chunkY * 32 + localY) * scale + offsetY);
        float baseZ = (float) ((chunkZ * 32 + localZ) * scale + offsetZ);
        float x0 = baseX, x1 = baseX + scale;
        float y0 = baseY, y1 = baseY + scale;
        float z0 = baseZ, z1 = baseZ + scale;

        switch (dir) {
            case DOWN -> {
                // y0 face: (x0, z1), (x0, z0), (x1, z0), (x1, z1)
                positions = new float[]{
                    x0, y0, z1,
                    x0, y0, z0,
                    x1, y0, z0,
                    x1, y0, z1
                };
            }
            case UP -> {
                // y1 face: (x0, z0), (x0, z1), (x1, z1), (x1, z0)
                positions = new float[]{
                    x0, y1, z0,
                    x0, y1, z1,
                    x1, y1, z1,
                    x1, y1, z0
                };
            }
            case NORTH -> {
                // z0 face: (x1, y1), (x1, y0), (x0, y0), (x0, y1)
                positions = new float[]{
                    x1, y1, z0,
                    x1, y0, z0,
                    x0, y0, z0,
                    x0, y1, z0
                };
            }
            case SOUTH -> {
                // z1 face: (x0, y1), (x0, y0), (x1, y0), (x1, y1)
                positions = new float[]{
                    x0, y1, z1,
                    x0, y0, z1,
                    x1, y0, z1,
                    x1, y1, z1
                };
            }
            case WEST -> {
                positions = new float[]{
                    x0, y1, z0,
                    x0, y0, z0,
                    x0, y0, z1,
                    x0, y1, z1
                };
            }
            case EAST -> {
                // x1 face: (x1, y1, z0), (x1, y0, z0), (x1, y0, z1), (x1, y1, z1)
                positions = new float[]{
                    x1, y1, z1,
                    x1, y0, z1,
                    x1, y0, z0,
                    x1, y1, z0
                };
            }
        }
        
        // Simple UVs (0-1)
        uvs = new float[]{
            0, 0,
            0, 1,
            1, 1,
            1, 0
        };

        sink.addQuad(
            materialKey,
            spriteName,
            null, // No overlay
            positions,
            uvs,
            null, // No biome UVs for now
            normals,
            colors,
            false
        );
        stats.faces++;
    }
    
    // Removed emitDebugQuad

}
