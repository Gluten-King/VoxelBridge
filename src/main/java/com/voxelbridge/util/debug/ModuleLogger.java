package com.voxelbridge.util.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe logger for a specific module.
 * Each module has its own log file and writer.
 */
final class ModuleLogger {

    private final LogModule module;
    private final Path logFile;
    private final LogLevel minLevel;
    private final ReentrantLock lock = new ReentrantLock();

    private BufferedWriter writer;
    private boolean initialized = false;

    ModuleLogger(LogModule module, Path outDir, LogLevel minLevel) {
        this.module = module;
        this.logFile = outDir.resolve(module.getFileName());
        this.minLevel = minLevel;
    }

    /**
     * Initializes the log file writer.
     */
    void initialize() throws IOException {
        lock.lock();
        try {
            if (initialized) {
                return;
            }

            // Ensure directory exists
            Path parentDir = logFile.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // Create or truncate the log file
            writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            initialized = true;

            // Write initialization message
            String initMessage = LogFormatter.format(LogLevel.INFO, module,
                    "Log file initialized: " + logFile);
            writeInternal(initMessage);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Logs a message if the level is enabled.
     */
    void log(LogLevel level, String message) {
        // Early exit if level is not enabled
        if (!level.isEnabled(minLevel)) {
            return;
        }

        if (!initialized) {
            return;
        }

        String formatted = LogFormatter.format(level, module, message);
        writeInternal(formatted);
    }

    /**
     * Logs a message with an exception if the level is enabled.
     */
    void log(LogLevel level, String message, Throwable throwable) {
        // Early exit if level is not enabled
        if (!level.isEnabled(minLevel)) {
            return;
        }

        if (!initialized) {
            return;
        }

        String formatted = LogFormatter.format(level, module, message, throwable);
        writeInternal(formatted);
    }

    /**
     * Writes a raw formatted message to the log file.
     */
    void writeRaw(String formattedMessage) {
        if (!initialized) {
            return;
        }
        writeInternal(formattedMessage);
    }

    /**
     * Internal write method with thread safety.
     */
    private void writeInternal(String message) {
        lock.lock();
        try {
            if (writer != null) {
                writer.write(message);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            // Log write failure, but don't propagate exception
            // to avoid breaking the export process
            System.err.printf("[ModuleLogger][%s] Failed to write log: %s%n",
                    module.name(), e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the log writer.
     */
    void close() {
        lock.lock();
        try {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    System.err.printf("[ModuleLogger][%s] Failed to close log: %s%n",
                            module.name(), e.getMessage());
                } finally {
                    writer = null;
                    initialized = false;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if this logger is initialized.
     */
    boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the log file path.
     */
    Path getLogFile() {
        return logFile;
    }

    /**
     * Gets the module this logger is for.
     */
    LogModule getModule() {
        return module;
    }
}
