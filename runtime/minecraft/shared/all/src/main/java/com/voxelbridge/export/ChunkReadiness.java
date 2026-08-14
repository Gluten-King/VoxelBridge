package com.voxelbridge.export;

import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Exact-runtime checks used by the streaming region coordinator. */
final class ChunkReadiness {
    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] ALL_OFFSETS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private ChunkReadiness() {}

    static boolean neighborsReady(ChunkPos chunkPos,
                                  int minChunkX,
                                  int maxChunkX,
                                  int minChunkZ,
                                  int maxChunkZ,
                                  ClientChunkCache chunkCache,
                                  boolean includeDiagonals,
                                  ChunkPos playerChunk,
                                  int activeDistance) {
        int[][] offsets = includeDiagonals ? ALL_OFFSETS : CARDINAL_OFFSETS;
        for (int[] offset : offsets) {
            int neighborX = chunkPos.x + offset[0];
            int neighborZ = chunkPos.z + offset[1];
            if (neighborX < minChunkX || neighborX > maxChunkX
                    || neighborZ < minChunkZ || neighborZ > maxChunkZ) {
                continue;
            }
            if (playerChunk != null && activeDistance > 0) {
                int distance = Math.max(
                    Math.abs(neighborX - playerChunk.x),
                    Math.abs(neighborZ - playerChunk.z));
                if (distance > activeDistance) continue;
            }
            LevelChunk neighbor = chunkCache.getChunk(neighborX, neighborZ, false);
            if (neighbor == null || neighbor.isEmpty()) return false;
        }
        return true;
    }

    static boolean isRenderable(Level level, ChunkPos chunkPos) {
        if (!(level instanceof ClientLevel clientLevel)) return true;
        Minecraft minecraft = ClientAccessHolder.get().getMinecraft();
        if (minecraft.player != null && minecraft.options != null) {
            ChunkPos player = minecraft.player.chunkPosition();
            int distance = Math.max(
                Math.abs(chunkPos.x - player.x),
                Math.abs(chunkPos.z - player.z));
            if (distance > minecraft.options.getEffectiveRenderDistance()) return true;
        }
        ClientChunkCache cache = clientLevel.getChunkSource();
        LevelChunk chunk = cache.getChunk(chunkPos.x, chunkPos.z, false);
        return chunk != null && !chunk.isEmpty();
    }
}
