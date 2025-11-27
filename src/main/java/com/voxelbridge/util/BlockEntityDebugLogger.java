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
 * Dedicated debug logger for block-entity texture/UV diagnostics.
 */
public final class BlockEntityDebugLogger {

    // Disabled for release builds to avoid generating large debug logs.
    private static final boolean ENABLED = false;

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    private static BufferedWriter writer;

    private BlockEntityDebugLogger() {}

    public static synchronized void initialize(Path outDir) {
        writer = null;
        if (ENABLED) {
            try {
                close();
                Path logPath = outDir.resolve("blockentity-debug.log");
                writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("BlockEntity debug log initialized");
            } catch (IOException e) {
                System.err.printf("[BlockEntityDebugLogger] Failed to initialize: %s%n", e.getMessage());
            }
        }
    }

    public static synchronized void log(String message) {
        if (ENABLED && writer != null) {
            try {
                writer.write(String.format("[%s] %s%n", TIMESTAMP.format(LocalDateTime.now()), message));
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[BlockEntityDebugLogger] Failed to write log: %s%n", e.getMessage());
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
        } else {
            writer = null;
        }
    }
}
