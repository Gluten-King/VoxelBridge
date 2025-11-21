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
 * Simple export log writer that records debugging data per export run.
 */
public final class ExportLogger {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    private static BufferedWriter writer;

    private ExportLogger() {}

    public static synchronized void initialize(Path outDir) throws IOException {
        close();
        Path logPath = outDir.resolve("voxelbridge-debug.log");
        writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log("Export log initialized");
    }

    public static synchronized void log(String message) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(String.format("[%s] %s%n", TIMESTAMP.format(LocalDateTime.now()), message));
            writer.flush();
        } catch (IOException e) {
            System.err.printf("[ExportLogger] Failed to write log: %s%n", e.getMessage());
        }
    }

    public static synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
        } finally {
            writer = null;
        }
    }
}
