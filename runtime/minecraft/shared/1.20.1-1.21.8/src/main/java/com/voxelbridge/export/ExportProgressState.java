package com.voxelbridge.export;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Mutable progress state owned by one export session (or a pre-export selection preview). */
final class ExportProgressState {
    private final Map<Long, ExportProgressTracker.ChunkState> chunkStates = new ConcurrentHashMap<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private volatile int total;
    private volatile long startNanos;
    private volatile ExportProgressTracker.Stage stage = ExportProgressTracker.Stage.IDLE;
    private volatile String stageDetail = "";
    private volatile Float phasePercent;
    private volatile boolean abortRequested;

    void clear() {
        chunkStates.clear();
        completed.set(0);
        failed.set(0);
        running.set(0);
        total = 0;
        startNanos = 0L;
        stage = ExportProgressTracker.Stage.IDLE;
        stageDetail = "";
        phasePercent = null;
        abortRequested = false;
    }

    void previewSelection(BlockPos first, BlockPos second) {
        clear();
        if (first == null || second == null) return;
        int minChunkX = Math.min(first.getX(), second.getX()) >> 4;
        int maxChunkX = Math.max(first.getX(), second.getX()) >> 4;
        int minChunkZ = Math.min(first.getZ(), second.getZ()) >> 4;
        int maxChunkZ = Math.max(first.getZ(), second.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunkStates.put(ChunkPos.asLong(chunkX, chunkZ), ExportProgressTracker.ChunkState.PENDING);
            }
        }
        total = chunkStates.size();
    }

    void initForExport(Set<Long> chunkKeys) {
        clear();
        if (chunkKeys != null) {
            for (Long key : chunkKeys) {
                if (key != null) chunkStates.put(key, ExportProgressTracker.ChunkState.PENDING);
            }
        }
        total = chunkStates.size();
        startNanos = System.nanoTime();
        stage = ExportProgressTracker.Stage.SAMPLING;
        stageDetail = "Sampling blocks";
    }

    void requestAbort() {
        abortRequested = true;
        stage = ExportProgressTracker.Stage.IDLE;
        stageDetail = "";
        phasePercent = null;
    }

    boolean isAbortRequested() {
        return abortRequested;
    }

    void setStage(ExportProgressTracker.Stage newStage, String detail) {
        stage = newStage != null ? newStage : ExportProgressTracker.Stage.IDLE;
        stageDetail = detail != null ? detail : "";
        phasePercent = null;
    }

    void markRunning(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        chunkStates.putIfAbsent(key, ExportProgressTracker.ChunkState.PENDING);
        ExportProgressTracker.ChunkState previous = chunkStates.put(key, ExportProgressTracker.ChunkState.RUNNING);
        if (previous == ExportProgressTracker.ChunkState.RUNNING) return;
        decrementTerminal(previous);
        running.incrementAndGet();
    }

    void markDone(int chunkX, int chunkZ) {
        transitionTerminal(chunkX, chunkZ, ExportProgressTracker.ChunkState.DONE);
    }

    void markFailed(int chunkX, int chunkZ) {
        transitionTerminal(chunkX, chunkZ, ExportProgressTracker.ChunkState.FAILED);
    }

    void markPending(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ExportProgressTracker.ChunkState previous = chunkStates.getOrDefault(
            key, ExportProgressTracker.ChunkState.PENDING);
        if (previous == ExportProgressTracker.ChunkState.PENDING) return;
        chunkStates.put(key, ExportProgressTracker.ChunkState.PENDING);
        decrement(previous);
    }

    Set<ChunkPos> pendingChunks() {
        return chunkStates.entrySet().stream()
            .filter(entry -> entry.getValue() == ExportProgressTracker.ChunkState.PENDING)
            .map(entry -> new ChunkPos(entry.getKey()))
            .collect(java.util.stream.Collectors.toSet());
    }

    Map<Long, ExportProgressTracker.ChunkState> snapshot() {
        return Collections.unmodifiableMap(chunkStates);
    }

    ExportProgressTracker.Progress progress() {
        return new ExportProgressTracker.Progress(
            completed.get(), failed.get(), total, running.get(), startNanos,
            stage, stageDetail, phasePercent);
    }

    void setPhasePercent(Float percent) {
        phasePercent = percent == null ? null : Math.max(0f, Math.min(1f, percent));
    }

    private void transitionTerminal(int chunkX,
                                    int chunkZ,
                                    ExportProgressTracker.ChunkState target) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ExportProgressTracker.ChunkState previous = chunkStates.getOrDefault(
            key, ExportProgressTracker.ChunkState.PENDING);
        if (previous == target) return;
        chunkStates.put(key, target);
        decrement(previous);
        if (target == ExportProgressTracker.ChunkState.DONE) completed.incrementAndGet();
        else failed.incrementAndGet();
    }

    private void decrementTerminal(ExportProgressTracker.ChunkState state) {
        if (state == ExportProgressTracker.ChunkState.DONE) completed.decrementAndGet();
        else if (state == ExportProgressTracker.ChunkState.FAILED) failed.decrementAndGet();
    }

    private void decrement(ExportProgressTracker.ChunkState state) {
        decrementTerminal(state);
        if (state == ExportProgressTracker.ChunkState.RUNNING) running.decrementAndGet();
    }
}
