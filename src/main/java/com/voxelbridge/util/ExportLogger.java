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

    // Disabled to avoid generating large debug logs during exports
    private static final boolean ENABLED = false;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    private static BufferedWriter writer;
    private static BufferedWriter animationWriter;  // Separate animation log

    private ExportLogger() {}

    public static synchronized void initialize(Path outDir) throws IOException {
        writer = null;
        animationWriter = null;
        if (ENABLED) {
            close();
            Path logPath = outDir.resolve("voxelbridge-debug.log");
            writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            // Create separate animation log
            Path animLogPath = outDir.resolve("animation-detection.log");
            animationWriter = Files.newBufferedWriter(animLogPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log("Export log initialized");
            logAnimation("Animation detection log initialized");
        }
    }

    public static synchronized void log(String message) {
        String logLine = String.format("[%s] %s", TIMESTAMP.format(LocalDateTime.now()), message);

        // Always output to console for immediate feedback
        System.out.println(logLine);

        // Also write to file if enabled
        if (ENABLED && writer != null) {
            try {
                writer.write(logLine);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[ExportLogger] Failed to write log: %s%n", e.getMessage());
            }
        }
    }

    public static synchronized void logAnimation(String message) {
        String logLine = String.format("[%s] %s", TIMESTAMP.format(LocalDateTime.now()), message);

        // Always output to console for immediate feedback
        System.out.println(logLine);

        // Write to animation log if enabled
        if (ENABLED && animationWriter != null) {
            try {
                animationWriter.write(logLine);
                animationWriter.newLine();
                animationWriter.flush();
            } catch (IOException e) {
                System.err.printf("[ExportLogger] Failed to write animation log: %s%n", e.getMessage());
            }
        }
    }

    public static synchronized void close() {
        if (ENABLED) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                } finally {
                    writer = null;
                }
            }
            if (animationWriter != null) {
                try {
                    animationWriter.flush();
                    animationWriter.close();
                } catch (IOException ignored) {
                } finally {
                    animationWriter = null;
                }
            }
        }
    }
}
