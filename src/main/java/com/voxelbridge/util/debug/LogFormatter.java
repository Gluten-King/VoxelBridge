package com.voxelbridge.util.debug;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Formats log messages with timestamp, level, module, thread, and message content.
 */
public final class LogFormatter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    private LogFormatter() {}

    /**
     * Formats a standard log message.
     * Format: [timestamp][level][module][thread] message
     */
    public static String format(LogLevel level, LogModule module, String message) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String threadName = Thread.currentThread().getName();
        return String.format("[%s][%s][%s][%s] %s",
                timestamp,
                level.name(),
                module.name().toLowerCase(),
                threadName,
                message);
    }

    /**
     * Formats a log message with an exception.
     * Includes the exception message and stack trace.
     */
    public static String format(LogLevel level, LogModule module, String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(format(level, module, message));

        if (throwable != null) {
            sb.append("\n");
            sb.append("Exception: ").append(throwable.getClass().getName());
            sb.append(": ").append(throwable.getMessage());
            sb.append("\n");

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw.toString());
        }

        return sb.toString();
    }

    /**
     * Formats a performance log message.
     * Format: [timestamp][PERF][module][thread] message
     */
    public static String formatPerformance(String message) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String threadName = Thread.currentThread().getName();
        return String.format("[%s][PERF][performance][%s] %s",
                timestamp,
                threadName,
                message);
    }

    /**
     * Formats a stat log message.
     * Format: [timestamp][STAT][module][thread] message
     */
    public static String formatStat(String message) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String threadName = Thread.currentThread().getName();
        return String.format("[%s][STAT][performance][%s] %s",
                timestamp,
                threadName,
                message);
    }

    /**
     * Formats a size log message.
     * Format: [timestamp][SIZE][module][thread] message
     */
    public static String formatSize(String message) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String threadName = Thread.currentThread().getName();
        return String.format("[%s][SIZE][performance][%s] %s",
                timestamp,
                threadName,
                message);
    }

    /**
     * Formats a memory log message.
     * Format: [timestamp][MEMORY][module][thread] message
     */
    public static String formatMemory(String message) {
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        String threadName = Thread.currentThread().getName();
        return String.format("[%s][MEMORY][performance][%s] %s",
                timestamp,
                threadName,
                message);
    }
}
