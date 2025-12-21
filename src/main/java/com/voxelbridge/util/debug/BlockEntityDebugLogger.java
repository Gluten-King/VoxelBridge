package com.voxelbridge.util.debug;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Legacy BlockEntity debug logger - now delegates to VoxelBridgeLogger.
 *
 * @deprecated Use {@link VoxelBridgeLogger} with LogModule.BLOCKENTITY for new code.
 *             This class is maintained for backward compatibility only.
 */
@Deprecated
public final class BlockEntityDebugLogger {

    private BlockEntityDebugLogger() {}

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
            VoxelBridgeLogger.error(LogModule.BLOCKENTITY, "Failed to initialize: " + e.getMessage(), e);
        }
    }

    /**
     * Logs a BlockEntity debug message.
     * Now delegates to VoxelBridgeLogger with BLOCKENTITY module.
     *
     * @param message The message to log
     * @deprecated Use {@link VoxelBridgeLogger#debug(LogModule, String)} with LogModule.BLOCKENTITY
     */
    @Deprecated
    public static synchronized void log(String message) {
        VoxelBridgeLogger.debug(LogModule.BLOCKENTITY, message);
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
