package com.voxelbridge.export;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks chunk-level export progress for rendering and HUD updates.
 */
@OnlyIn(Dist.CLIENT)
public final class ExportProgressTracker {
    public enum ChunkState {
        PENDING, // Not started
        RUNNING, // Currently exporting
        DONE,    // Successfully completed
        FAILED   // Failed (chunk not loaded or error)
    }

    public enum Stage {
        IDLE,
        SAMPLING,
        ATLAS,
        FINALIZE,
        COMPLETE
    }

    private static final Map<Long, ChunkState> chunkStates = new ConcurrentHashMap<>();
    private static final AtomicInteger completed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static final AtomicInteger running = new AtomicInteger();
    private static volatile int total = 0;
    private static volatile long startNanos = 0L;
    private static volatile Stage stage = Stage.IDLE;
    private static volatile String stageDetail = "";

    private ExportProgressTracker() {}

    public static void clear() {
        chunkStates.clear();
        completed.set(0);
        failed.set(0);
        running.set(0);
        total = 0;
        startNanos = 0L;
        stage = Stage.IDLE;
        stageDetail = "";
    }

    /**
     * Pre-compute the chunk set for a selection so unloaded regions can highlight immediately (in red).
     */
    public static void previewSelection(BlockPos pos1, BlockPos pos2) {
        clear();
        if (pos1 == null || pos2 == null) {
            return;
        }
        int minChunkX = Math.min(pos1.getX(), pos2.getX()) >> 4;
        int maxChunkX = Math.max(pos1.getX(), pos2.getX()) >> 4;
        int minChunkZ = Math.min(pos1.getZ(), pos2.getZ()) >> 4;
        int maxChunkZ = Math.max(pos1.getZ(), pos2.getZ()) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkStates.put(ChunkPos.asLong(cx, cz), ChunkState.PENDING);
            }
        }
        total = chunkStates.size();
    }

    /**
     * Called by StreamingRegionSampler once the task list is finalized so the tracker matches the actual workload.
     */
    public static void initForExport(Set<Long> chunkKeys) {
        chunkStates.clear();
        completed.set(0);
        failed.set(0);
        running.set(0);
        for (Long key : chunkKeys) {
            chunkStates.put(key, ChunkState.PENDING);
        }
        total = chunkStates.size();
        startNanos = System.nanoTime();
        stage = Stage.SAMPLING;
        stageDetail = "采样方块";
    }

    public static void setStage(Stage newStage, String detail) {
        stage = newStage;
        stageDetail = (detail != null) ? detail : "";
    }

    public static void markRunning(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        chunkStates.putIfAbsent(key, ChunkState.PENDING);
        ChunkState prev = chunkStates.put(key, ChunkState.RUNNING);
        if (prev != ChunkState.RUNNING) {
            if (prev == ChunkState.DONE) completed.decrementAndGet();
            if (prev == ChunkState.FAILED) failed.decrementAndGet();
            running.incrementAndGet();
        }
    }

    public static void markDone(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        ChunkState prev = chunkStates.getOrDefault(key, ChunkState.PENDING);
        if (prev == ChunkState.DONE) {
            return;
        }
        chunkStates.put(key, ChunkState.DONE);
        if (prev == ChunkState.FAILED) {
            failed.decrementAndGet();
        }
        if (prev == ChunkState.RUNNING) {
            running.decrementAndGet();
        }
        completed.incrementAndGet();
    }

    public static void markFailed(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        ChunkState prev = chunkStates.getOrDefault(key, ChunkState.PENDING);
        if (prev == ChunkState.FAILED) {
            return;
        }
        chunkStates.put(key, ChunkState.FAILED);
        if (prev == ChunkState.DONE) {
            completed.decrementAndGet();
        }
        if (prev == ChunkState.RUNNING) {
            running.decrementAndGet();
        }
        failed.incrementAndGet();
    }

    /**
     * Explicitly reset a chunk back to PENDING (used when it is outside the active/visible window).
     */
    public static void markPending(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        ChunkState prev = chunkStates.getOrDefault(key, ChunkState.PENDING);
        if (prev == ChunkState.PENDING) {
            return;
        }
        chunkStates.put(key, ChunkState.PENDING);
        if (prev == ChunkState.DONE) {
            completed.decrementAndGet();
        } else if (prev == ChunkState.FAILED) {
            failed.decrementAndGet();
        } else if (prev == ChunkState.RUNNING) {
            running.decrementAndGet();
        }
    }

    public static Set<ChunkPos> getPendingChunks() {
        return chunkStates.entrySet().stream()
            .filter(e -> e.getValue() == ChunkState.PENDING)
            .map(e -> new ChunkPos(e.getKey()))
            .collect(java.util.stream.Collectors.toSet());
    }

    public static Map<Long, ChunkState> snapshot() {
        return Collections.unmodifiableMap(chunkStates);
    }

    public static Progress progress() {
        return new Progress(completed.get(), failed.get(), total, running.get(), startNanos, stage, stageDetail);
    }

    public record Progress(int done, int failed, int total, int running, long startNanos, Stage stage, String stageDetail) {
        public float percent() {
            return total == 0 ? 0f : (done * 100f) / total;
        }

        public int pending() {
            return total - done - failed;
        }

        public boolean isComplete() {
            return done + failed == total;
        }

        public double elapsedSeconds() {
            if (startNanos == 0L) return 0d;
            return (System.nanoTime() - startNanos) / 1_000_000_000.0;
        }
    }
}
