package com.voxelbridge.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight timing logger for export phases.
 * Writes durations to a dedicated log file separate from the debug log.
 */
public final class TimeLogger {
    private static final boolean ENABLED = true;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    private static BufferedWriter writer;

    private TimeLogger() {}

    public static synchronized void initialize(Path outDir) throws IOException {
        writer = null;
        if (ENABLED) {
            close();
            Path logPath = outDir.resolve("timelog.log");
            writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            logLine("Time log initialized");
        }
    }

    public static long now() {
        return System.nanoTime();
    }

    public static long elapsedSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    public static void logDuration(String section, long nanos) {
        if (!ENABLED) return;
        double ms = nanos / 1_000_000.0;
        logLine(String.format("%s: %.3f ms", section, ms));
    }

    /**
     * Log a numeric stat (e.g., counts).
     */
    public static void logStat(String label, long value) {
        if (!ENABLED) return;
        logLine(String.format("%s: %d", label, value));
    }

    /**
     * Log a size in bytes with a human-readable MB view.
     */
    public static void logSize(String label, long bytes) {
        if (!ENABLED) return;
        double mb = bytes / 1024.0 / 1024.0;
        logLine(String.format("%s: %.2f MB (%,d bytes)", label, mb, bytes));
    }

    /**
     * Log a free-form informational message.
     */
    public static void logInfo(String message) {
        if (!ENABLED) return;
        logLine(message);
    }

    /**
     * Log current memory usage statistics.
     * @param label Label for this memory snapshot (e.g., "before_atlas", "after_geometry")
     */
    public static void logMemory(String label) {
        if (!ENABLED) return;
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory = rt.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        logLine(String.format("memory_%s: used=%.1f MB, total=%.1f MB, max=%.1f MB, usage=%.1f%%",
            label,
            usedMemory / 1024.0 / 1024.0,
            totalMemory / 1024.0 / 1024.0,
            maxMemory / 1024.0 / 1024.0,
            (usedMemory * 100.0) / maxMemory));
    }

    private static synchronized void logLine(String message) {
        if (ENABLED && writer != null) {
            try {
                writer.write(String.format("[%s] %s%n", TIMESTAMP.format(LocalDateTime.now()), message));
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[TimeLogger] Failed to write log: %s%n", e.getMessage());
            }
        }
    }

    public static synchronized void close() {
        if (ENABLED && writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            } finally {
                writer = null;
            }
        }
    }
}
