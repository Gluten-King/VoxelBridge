package com.voxelbridge.util.debug;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Unified logging facade backed by log4j2.
 * Uses dedicated per-module file appenders with create-on-demand behavior.
 */
public final class VoxelBridgeLogger {

    private static final String LOGGER_PREFIX = "voxelbridge.";
    private static final String PATTERN = "[%d{yyyy-MM-dd'T'HH:mm:ss.SSS}][%p][%c{1}][%t] %m%n";

    private static final Map<LogModule, Logger> LOGGERS = new EnumMap<>(LogModule.class);
    private static final Map<LogModule, String> APPENDER_NAMES = new EnumMap<>(LogModule.class);

    private static volatile boolean initialized = false;
    private static Path outputDir;

    private VoxelBridgeLogger() {}

    public static synchronized void initialize(Path outDir) throws IOException {
        if (initialized) {
            if (outputDir != null && outputDir.equals(outDir)) {
                return;
            }
            close();
        }
        Files.createDirectories(outDir);
        outputDir = outDir;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        for (LogModule module : LogModule.values()) {
            String appenderName = "VB_" + module.name();
            APPENDER_NAMES.put(module, appenderName);
            if (config.getAppender(appenderName) == null) {
                PatternLayout layout = PatternLayout.newBuilder()
                    .withPattern(PATTERN)
                    .withConfiguration(config)
                    .build();
                String fileName = outputDir.resolve(module.getFileName()).toString();
                FileAppender appender = FileAppender.newBuilder()
                    .withName(appenderName)
                    .withFileName(fileName)
                    .withAppend(true)
                    .withImmediateFlush(true)
                    .withBufferedIo(true)
                    .withCreateOnDemand(true)
                    .setConfiguration(config)
                    .setLayout(layout)
                    .build();
                appender.start();
                config.addAppender(appender);
            }
            String loggerName = LOGGER_PREFIX + module.getLoggerName();
            LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.DEBUG, false);
            loggerConfig.addAppender(config.getAppender(appenderName), Level.DEBUG, null);
            config.addLogger(loggerName, loggerConfig);
            LOGGERS.put(module, LogManager.getLogger(loggerName));
        }

        ctx.updateLoggers();
        initialized = true;
    }

    public static synchronized void close() {
        if (!initialized) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        for (LogModule module : LogModule.values()) {
            String loggerName = LOGGER_PREFIX + module.getLoggerName();
            config.removeLogger(loggerName);
            String appenderName = APPENDER_NAMES.get(module);
            if (appenderName != null) {
                Appender appender = config.getAppender(appenderName);
                if (appender != null) {
                    appender.stop();
                    config.getAppenders().remove(appenderName);
                }
            }
        }
        ctx.updateLoggers();
        LOGGERS.clear();
        APPENDER_NAMES.clear();
        initialized = false;
    }

    public static void closeModule(LogModule module) {
        if (!initialized) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        String loggerName = LOGGER_PREFIX + module.getLoggerName();
        config.removeLogger(loggerName);
        String appenderName = APPENDER_NAMES.get(module);
        if (appenderName != null) {
            Appender appender = config.getAppender(appenderName);
            if (appender != null) {
                appender.stop();
                config.getAppenders().remove(appenderName);
            }
        }
        ctx.updateLoggers();
        LOGGERS.remove(module);
    }

    public static void error(LogModule module, String message) {
        log(module, Level.ERROR, message, null);
    }

    public static void error(LogModule module, String message, Throwable throwable) {
        log(module, Level.ERROR, message, throwable);
    }

    public static void warn(LogModule module, String message) {
        log(module, Level.WARN, message, null);
    }

    public static void info(LogModule module, String message) {
        log(module, Level.INFO, message, null);
    }

    public static void debug(LogModule module, String message) {
        log(module, Level.DEBUG, message, null);
    }

    public static void trace(LogModule module, String message) {
        log(module, Level.TRACE, message, null);
    }

    public static void duration(String section, long nanos) {
        double ms = nanos / 1_000_000.0;
        String message = String.format("duration %s: %.3f ms", section, ms);
        info(LogModule.PERFORMANCE, message);
    }

    public static long now() {
        return System.nanoTime();
    }

    public static long elapsedSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    public static void stat(String label, long value) {
        String message = String.format("stat %s: %d", label, value);
        info(LogModule.PERFORMANCE, message);
    }

    public static void size(String label, long bytes) {
        double mb = bytes / 1024.0 / 1024.0;
        String message = String.format("size %s: %.2f MB (%,d bytes)", label, mb, bytes);
        info(LogModule.PERFORMANCE, message);
    }

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

        info(LogModule.PERFORMANCE, message);
    }

    public static void logInfo(String message) {
        info(LogModule.PERFORMANCE, message);
    }

    public static boolean isDebugEnabled(LogModule module) {
        Logger logger = getLogger(module);
        return logger != null && logger.isDebugEnabled();
    }

    public static boolean isTraceEnabled(LogModule module) {
        Logger logger = getLogger(module);
        return logger != null && logger.isTraceEnabled();
    }

    private static void log(LogModule module, Level level, String message, Throwable throwable) {
        Logger logger = getLogger(module);
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.log(level, message);
        } else {
            logger.log(level, message, throwable);
        }
    }

    private static Logger getLogger(LogModule module) {
        if (!initialized) {
            return null;
        }
        Logger logger = LOGGERS.get(module);
        if (logger != null) {
            return logger;
        }
        String loggerName = LOGGER_PREFIX + module.getLoggerName();
        logger = LogManager.getLogger(loggerName);
        LOGGERS.put(module, logger);
        return logger;
    }
}
