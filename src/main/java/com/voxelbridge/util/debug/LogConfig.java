package com.voxelbridge.util.debug;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configuration for the unified VoxelBridge logging system.
 * Allows per-module log level configuration.
 */
public final class LogConfig {

    private final Map<LogModule, LogLevel> moduleLevels;
    private final boolean enabled;
    private final int bufferSize;

    private LogConfig(Map<LogModule, LogLevel> moduleLevels, boolean enabled, int bufferSize) {
        this.moduleLevels = new EnumMap<>(moduleLevels);
        this.enabled = enabled;
        this.bufferSize = bufferSize;
    }

    /**
     * Creates a default configuration with INFO level for all modules.
     */
    public static LogConfig defaults() {
        Map<LogModule, LogLevel> levels = new EnumMap<>(LogModule.class);
        levels.put(LogModule.EXPORT, LogLevel.INFO);
        levels.put(LogModule.TEXTURE, LogLevel.INFO);
        levels.put(LogModule.ANIMATION, LogLevel.INFO);
        levels.put(LogModule.PERFORMANCE, LogLevel.INFO);
        levels.put(LogModule.BLOCKENTITY, LogLevel.DEBUG);
        levels.put(LogModule.GLTF, LogLevel.INFO);
        levels.put(LogModule.VXB, LogLevel.INFO);

        return new LogConfig(levels, true, 8192);
    }

    /**
     * Gets the minimum log level for a specific module.
     */
    public LogLevel getLevel(LogModule module) {
        return moduleLevels.getOrDefault(module, LogLevel.INFO);
    }

    /**
     * Checks if logging is enabled globally.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the buffer size for log writers.
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Builder for creating custom LogConfig instances.
     */
    public static class Builder {
        private final Map<LogModule, LogLevel> moduleLevels = new EnumMap<>(LogModule.class);
        private boolean enabled = true;
        private int bufferSize = 8192;

        public Builder() {
            // Initialize with defaults
            for (LogModule module : LogModule.values()) {
                moduleLevels.put(module, LogLevel.INFO);
            }
        }

        public Builder setLevel(LogModule module, LogLevel level) {
            moduleLevels.put(module, level);
            return this;
        }

        public Builder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public LogConfig build() {
            return new LogConfig(moduleLevels, enabled, bufferSize);
        }
    }
}
