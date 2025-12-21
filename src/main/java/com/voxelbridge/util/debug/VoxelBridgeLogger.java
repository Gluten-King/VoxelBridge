package com.voxelbridge.util.debug;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Unified logging facade for VoxelBridge.
 * Provides centralized, module-based logging with file-only output.
 *
 * Features:
 * - Module-based log file routing (export.log, texture.log, etc.)
 * - Log levels: ERROR, WARN, INFO, DEBUG, TRACE
 * - File-only output (no console logging)
 * - Thread-safe implementation
 * - Performance metrics logging (duration, memory, stats, sizes)
 *
 * Usage:
 * <pre>
 * // Initialize at export start
 * VoxelBridgeLogger.initialize(outDir);
 *
 * // Log messages
 * VoxelBridgeLogger.info(LogModule.EXPORT, "Export started");
 * VoxelBridgeLogger.error(LogModule.TEXTURE, "Failed to load texture", exception);
 *
 * // Performance logging
 * VoxelBridgeLogger.duration("block_sampling", nanos);
 * VoxelBridgeLogger.memory("after_atlas");
 *
 * // Close at export end
 * VoxelBridgeLogger.close();
 * </pre>
 */
public final class VoxelBridgeLogger {

    private VoxelBridgeLogger() {}

    // ==================== Lifecycle Management ====================

    /**
     * Initializes the logging system with default configuration.
     * Creates log files in the specified output directory.
     *
     * @param outDir Directory where log files will be created
     * @throws IOException if log files cannot be created
     */
    public static void initialize(Path outDir) throws IOException {
        LoggerManager.getInstance().initialize(outDir);
    }

    /**
     * Initializes the logging system with custom configuration.
     *
     * @param outDir Directory where log files will be created
     * @param config Custom logging configuration
     * @throws IOException if log files cannot be created
     */
    public static void initialize(Path outDir, LogConfig config) throws IOException {
        LoggerManager.getInstance().initialize(outDir, config);
    }

    /**
     * Closes all log files and releases resources.
     * Should be called at the end of export.
     */
    public static void close() {
        LoggerManager.getInstance().close();
    }

    /**
     * Closes a specific module's log file.
     *
     * @param module The module to close
     */
    public static void closeModule(LogModule module) {
        LoggerManager.getInstance().closeModule(module);
    }

    // ==================== Basic Logging Methods ====================

    /**
     * Logs an ERROR level message.
     *
     * @param module The module to log to
     * @param message The log message
     */
    public static void error(LogModule module, String message) {
        log(module, LogLevel.ERROR, message);
    }

    /**
     * Logs an ERROR level message with an exception.
     *
     * @param module The module to log to
     * @param message The log message
     * @param throwable The exception to log
     */
    public static void error(LogModule module, String message, Throwable throwable) {
        log(module, LogLevel.ERROR, message, throwable);
    }

    /**
     * Logs a WARN level message.
     *
     * @param module The module to log to
     * @param message The log message
     */
    public static void warn(LogModule module, String message) {
        log(module, LogLevel.WARN, message);
    }

    /**
     * Logs an INFO level message.
     *
     * @param module The module to log to
     * @param message The log message
     */
    public static void info(LogModule module, String message) {
        log(module, LogLevel.INFO, message);
    }

    /**
     * Logs a DEBUG level message.
     *
     * @param module The module to log to
     * @param message The log message
     */
    public static void debug(LogModule module, String message) {
        log(module, LogLevel.DEBUG, message);
    }

    /**
     * Logs a TRACE level message.
     *
     * @param module The module to log to
     * @param message The log message
     */
    public static void trace(LogModule module, String message) {
        log(module, LogLevel.TRACE, message);
    }

    // ==================== Performance Logging ====================

    /**
     * Logs a duration measurement.
     * Format: [timestamp][PERF][performance][thread] section: X.XXX ms
     *
     * @param section Name of the timed section
     * @param nanos Duration in nanoseconds
     */
    public static void duration(String section, long nanos) {
        double ms = nanos / 1_000_000.0;
        String message = String.format("%s: %.3f ms", section, ms);
        String formatted = LogFormatter.formatPerformance(message);
        writeToModule(LogModule.PERFORMANCE, formatted);
    }

    /**
     * Logs a numeric statistic.
     * Format: [timestamp][STAT][performance][thread] label: value
     *
     * @param label Label for the statistic
     * @param value Numeric value
     */
    public static void stat(String label, long value) {
        String message = String.format("%s: %d", label, value);
        String formatted = LogFormatter.formatStat(message);
        writeToModule(LogModule.PERFORMANCE, formatted);
    }

    /**
     * Logs a size in bytes with human-readable format.
     * Format: [timestamp][SIZE][performance][thread] label: X.XX MB (Y bytes)
     *
     * @param label Label for the size
     * @param bytes Size in bytes
     */
    public static void size(String label, long bytes) {
        double mb = bytes / 1024.0 / 1024.0;
        String message = String.format("%s: %.2f MB (%,d bytes)", label, mb, bytes);
        String formatted = LogFormatter.formatSize(message);
        writeToModule(LogModule.PERFORMANCE, formatted);
    }

    /**
     * Logs current memory usage statistics.
     * Format: [timestamp][MEMORY][performance][thread] memory_label: used=X.X MB, total=X.X MB, max=X.X MB, usage=X.X%
     *
     * @param label Label for this memory snapshot (e.g., "before_atlas", "after_geometry")
     */
    public static void memory(String label) {
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory = rt.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        String message = String.format("memory_%s: used=%.1f MB, total=%.1f MB, max=%.1f MB, usage=%.1f%%",
                label,
                usedMemory / 1024.0 / 1024.0,
                totalMemory / 1024.0 / 1024.0,
                maxMemory / 1024.0 / 1024.0,
                (usedMemory * 100.0) / maxMemory);

        String formatted = LogFormatter.formatMemory(message);
        writeToModule(LogModule.PERFORMANCE, formatted);
    }

    /**
     * Logs a free-form informational message to the performance log.
     *
     * @param message The message to log
     */
    public static void logInfo(String message) {
        info(LogModule.PERFORMANCE, message);
    }

    // ==================== Level Check Methods ====================

    /**
     * Checks if DEBUG level is enabled for a module.
     * Useful for avoiding expensive string operations when debug is disabled.
     *
     * @param module The module to check
     * @return true if DEBUG level is enabled
     */
    public static boolean isDebugEnabled(LogModule module) {
        return LoggerManager.getInstance().isLevelEnabled(module, LogLevel.DEBUG);
    }

    /**
     * Checks if TRACE level is enabled for a module.
     *
     * @param module The module to check
     * @return true if TRACE level is enabled
     */
    public static boolean isTraceEnabled(LogModule module) {
        return LoggerManager.getInstance().isLevelEnabled(module, LogLevel.TRACE);
    }

    // ==================== Internal Helper Methods ====================

    /**
     * Internal method to log a message.
     */
    private static void log(LogModule module, LogLevel level, String message) {
        LoggerManager manager = LoggerManager.getInstance();
        if (!manager.isInitialized()) {
            return;
        }

        ModuleLogger logger = manager.getLogger(module);
        if (logger != null) {
            logger.log(level, message);
        }
    }

    /**
     * Internal method to log a message with an exception.
     */
    private static void log(LogModule module, LogLevel level, String message, Throwable throwable) {
        LoggerManager manager = LoggerManager.getInstance();
        if (!manager.isInitialized()) {
            return;
        }

        ModuleLogger logger = manager.getLogger(module);
        if (logger != null) {
            logger.log(level, message, throwable);
        }
    }

    /**
     * Internal method to write a pre-formatted message to a module.
     */
    private static void writeToModule(LogModule module, String formattedMessage) {
        LoggerManager manager = LoggerManager.getInstance();
        if (!manager.isInitialized()) {
            return;
        }

        ModuleLogger logger = manager.getLogger(module);
        if (logger != null) {
            logger.writeRaw(formattedMessage);
        }
    }
}
