package com.voxelbridge.util.debug;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Singleton manager that coordinates all module loggers.
 * Handles initialization, configuration, and lifecycle management.
 */
final class LoggerManager {

    private static LoggerManager instance;

    private final Map<LogModule, ModuleLogger> loggers = new EnumMap<>(LogModule.class);
    private LogConfig config;
    private boolean initialized = false;

    private LoggerManager() {}

    /**
     * Gets the singleton instance.
     */
    static synchronized LoggerManager getInstance() {
        if (instance == null) {
            instance = new LoggerManager();
        }
        return instance;
    }

    /**
     * Initializes all module loggers with default configuration.
     */
    synchronized void initialize(Path outDir) throws IOException {
        initialize(outDir, LogConfig.defaults());
    }

    /**
     * Initializes all module loggers with custom configuration.
     */
    synchronized void initialize(Path outDir, LogConfig config) throws IOException {
        if (initialized) {
            // Close existing loggers first
            close();
        }

        this.config = config;

        if (!config.isEnabled()) {
            return;
        }

        // Create a ModuleLogger for each module
        for (LogModule module : LogModule.values()) {
            LogLevel minLevel = config.getLevel(module);
            ModuleLogger logger = new ModuleLogger(module, outDir, minLevel);
            logger.initialize();
            loggers.put(module, logger);
        }

        initialized = true;
    }

    /**
     * Gets the logger for a specific module.
     */
    ModuleLogger getLogger(LogModule module) {
        return loggers.get(module);
    }

    /**
     * Checks if the logging system is initialized.
     */
    boolean isInitialized() {
        return initialized;
    }

    /**
     * Checks if a specific log level is enabled for a module.
     */
    boolean isLevelEnabled(LogModule module, LogLevel level) {
        if (!initialized || config == null) {
            return false;
        }
        LogLevel minLevel = config.getLevel(module);
        return level.isEnabled(minLevel);
    }

    /**
     * Closes all module loggers.
     */
    synchronized void close() {
        for (ModuleLogger logger : loggers.values()) {
            logger.close();
        }
        loggers.clear();
        initialized = false;
    }

    /**
     * Closes a specific module logger.
     */
    synchronized void closeModule(LogModule module) {
        ModuleLogger logger = loggers.get(module);
        if (logger != null) {
            logger.close();
            loggers.remove(module);
        }
    }
}
