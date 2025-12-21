package com.voxelbridge.util.debug;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Legacy export logger - now delegates to VoxelBridgeLogger.
 *
 * @deprecated Use {@link VoxelBridgeLogger} directly for new code.
 *             This class is maintained for backward compatibility only.
 */
@Deprecated
public final class ExportLogger {

    private ExportLogger() {}

    /**
     * Initializes the logging system.
     * Now delegates to VoxelBridgeLogger.
     *
     * @param outDir Directory where log files will be created
     * @deprecated Use {@link VoxelBridgeLogger#initialize(Path)}
     */
    @Deprecated
    public static synchronized void initialize(Path outDir) {
        try {
            VoxelBridgeLogger.initialize(outDir);
        } catch (IOException e) {
            VoxelBridgeLogger.error(LogModule.EXPORT, "Failed to initialize log files: " + e.getMessage(), e);
        }
    }

    /**
     * Logs a message to the export log.
     * Now delegates to VoxelBridgeLogger with EXPORT module.
     *
     * @param message The message to log
     * @deprecated Use {@link VoxelBridgeLogger#info(LogModule, String)}
     */
    @Deprecated
    public static synchronized void log(String message) {
        VoxelBridgeLogger.info(LogModule.EXPORT, message);
    }

    /**
     * Logs a message to the animation detection log.
     * Now delegates to VoxelBridgeLogger with ANIMATION module.
     *
     * @param message The message to log
     * @deprecated Use {@link VoxelBridgeLogger#info(LogModule, String)} with LogModule.ANIMATION
     */
    @Deprecated
    public static synchronized void logAnimation(String message) {
        VoxelBridgeLogger.info(LogModule.ANIMATION, message);
    }

    /**
     * Logs a message to the glTF debug log.
     * Now delegates to VoxelBridgeLogger with GLTF module.
     *
     * @param message The message to log
     * @deprecated Use {@link VoxelBridgeLogger#debug(LogModule, String)} with LogModule.GLTF
     */
    @Deprecated
    public static synchronized void logGltfDebug(String message) {
        VoxelBridgeLogger.debug(LogModule.GLTF, message);
    }

    /**
     * Closes the logging system.
     * Now delegates to VoxelBridgeLogger.
     *
     * @deprecated Use {@link VoxelBridgeLogger#close()}
     */
    @Deprecated
    public static synchronized void close() {
        VoxelBridgeLogger.close();
    }
}
