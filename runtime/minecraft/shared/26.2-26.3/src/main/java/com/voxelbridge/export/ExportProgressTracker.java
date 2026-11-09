package com.voxelbridge.export;

import com.voxelbridge.pipeline.session.ExportSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;

/** UI façade over Minecraft 1.21.11 session-owned progress state. */
public final class ExportProgressTracker {
    public enum ChunkState { PENDING, RUNNING, DONE, FAILED }
    public enum Stage { IDLE, SAMPLING, ATLAS, PBR_DECODE, FINALIZE, COMPLETE }

    private static final String SESSION_KEY = "export:progress";
    private static final String FORMAT_LABEL = "glTF";
    private static volatile ExportProgressState12111 active = new ExportProgressState12111();

    private ExportProgressTracker() {}

    public static synchronized void bind(ExportSession session) {
        if (session == null) return;
        ExportProgressState12111 bound = session.computeAttribute(SESSION_KEY, () -> active);
        active = bound;
        session.onClose(() -> release(bound));
    }

    private static synchronized void release(ExportProgressState12111 bound) {
        if (active == bound) active = new ExportProgressState12111();
    }

    public static void clear() { active.clear(); }
    public static void previewSelection(BlockPos first, BlockPos second) { active.previewSelection(first, second); }
    public static void initForExport(Set<Long> chunkKeys) { active.initForExport(chunkKeys); }
    public static void requestAbort() { active.requestAbort(); }
    public static boolean isAbortRequested() { return active.isAbortRequested(); }
    public static void setStage(Stage stage, String detail) { active.setStage(stage, detail); }
    public static void markRunning(int chunkX, int chunkZ) { active.markRunning(chunkX, chunkZ); }
    public static void markDone(int chunkX, int chunkZ) { active.markDone(chunkX, chunkZ); }
    public static void markFailed(int chunkX, int chunkZ) { active.markFailed(chunkX, chunkZ); }
    public static void markPending(int chunkX, int chunkZ) { active.markPending(chunkX, chunkZ); }
    public static Set<ChunkPos> getPendingChunks() { return active.pendingChunks(); }
    public static Map<Long, ChunkState> snapshot() { return active.snapshot(); }
    public static Progress progress() { return active.progress(); }
    public static String getFormatLabel() { return FORMAT_LABEL; }
    public static void setPhasePercent(Float percent) { active.setPhasePercent(percent); }

    public record Progress(int done, int failed, int total, int running, long startNanos,
                           Stage stage, String stageDetail, Float phasePercent) {
        public float percent() { return total == 0 ? 0f : done * 100f / total; }
        public float displayPercent() { return phasePercent != null ? phasePercent * 100f : percent(); }
        public int pending() { return total - done - failed; }
        public boolean isComplete() { return done + failed == total; }
        public double elapsedSeconds() {
            return startNanos == 0L ? 0d : (System.nanoTime() - startNanos) / 1_000_000_000.0;
        }
    }
}
