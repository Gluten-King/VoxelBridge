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
