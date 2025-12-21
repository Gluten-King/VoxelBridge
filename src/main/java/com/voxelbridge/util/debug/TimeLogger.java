package com.voxelbridge.util.debug;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Legacy timing logger - now delegates to VoxelBridgeLogger.
 *
 * @deprecated Use {@link VoxelBridgeLogger} performance logging methods directly for new code.
 *             This class is maintained for backward compatibility only.
 */
@Deprecated
public final class TimeLogger {

    private TimeLogger() {}

    /**
     * Initializes the logging system.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param outDir Directory where log files will be created
     * @throws IOException if log files cannot be created
     * @deprecated Use {@link VoxelBridgeLogger#initialize(Path)}
     */
    @Deprecated
    public static synchronized void initialize(Path outDir) throws IOException {
        VoxelBridgeLogger.initialize(outDir);
    }

    /**
     * Gets current time in nanoseconds.
     *
     * @return Current System.nanoTime()
     */
    public static long now() {
        return System.nanoTime();
    }

    /**
     * Calculates elapsed time since a start point.
     *
     * @param startNanos Start time from now()
     * @return Elapsed nanoseconds
     */
    public static long elapsedSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    /**
     * Logs a duration measurement.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param section Name of the timed section
     * @param nanos Duration in nanoseconds
     * @deprecated Use {@link VoxelBridgeLogger#duration(String, long)}
     */
    @Deprecated
    public static void logDuration(String section, long nanos) {
        VoxelBridgeLogger.duration(section, nanos);
    }

    /**
     * Logs a numeric statistic.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param label Label for the statistic
     * @param value Numeric value
     * @deprecated Use {@link VoxelBridgeLogger#stat(String, long)}
     */
    @Deprecated
    public static void logStat(String label, long value) {
        VoxelBridgeLogger.stat(label, value);
    }

    /**
     * Logs a size in bytes.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param label Label for the size
     * @param bytes Size in bytes
     * @deprecated Use {@link VoxelBridgeLogger#size(String, long)}
     */
    @Deprecated
    public static void logSize(String label, long bytes) {
        VoxelBridgeLogger.size(label, bytes);
    }

    /**
     * Logs a free-form informational message.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param message The message to log
     * @deprecated Use {@link VoxelBridgeLogger#logInfo(String)}
     */
    @Deprecated
    public static void logInfo(String message) {
        VoxelBridgeLogger.logInfo(message);
    }

    /**
     * Logs current memory usage statistics.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param label Label for this memory snapshot
     * @deprecated Use {@link VoxelBridgeLogger#memory(String)}
     */
    @Deprecated
    public static void logMemory(String label) {
        VoxelBridgeLogger.memory(label);
    }

    /**
     * Closes the logging system.
     * Now delegates to VoxelBridgeLogger.
     *
     * @deprecated Use {@link VoxelBridgeLogger#close()}
     */
    @Deprecated
    public static synchronized void close() {
        VoxelBridgeLogger.close();
    }
}
