package com.voxelbridge.util.debug;

/**
 * Log level enumeration for the unified VoxelBridge logging system.
 * Levels are ordered from highest to lowest priority.
 */
public enum LogLevel {
    /**
     * Error level - critical errors that need immediate attention.
     */
    ERROR(0),

    /**
     * Warning level - potential issues that should be noted.
     */
    WARN(1),

    /**
     * Info level - general informational messages.
     */
    INFO(2),

    /**
     * Debug level - detailed debugging information.
     */
    DEBUG(3),

    /**
     * Trace level - very detailed trace information.
     */
    TRACE(4);

    private final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    /**
     * Checks if this level should be logged given a minimum level threshold.
     * @param minLevel The minimum level that should be logged
     * @return true if this level should be logged
     */
    public boolean isEnabled(LogLevel minLevel) {
        return this.priority <= minLevel.priority;
    }

    /**
     * Gets the priority value (lower = higher priority).
     */
    public int getPriority() {
        return priority;
    }
}
