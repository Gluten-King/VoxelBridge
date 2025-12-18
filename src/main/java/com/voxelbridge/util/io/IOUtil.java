package com.voxelbridge.util.io;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility helpers for export directory management.
 */
@OnlyIn(Dist.CLIENT)
public final class IOUtil {
    private IOUtil() {}

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Ensures the export root exists and returns the timestamped sub directory path.
     */
    public static Path ensureExportDir() throws IOException {
        Path exportRoot = Path.of("export");
        if (!Files.exists(exportRoot)) {
            Files.createDirectories(exportRoot);
        }

        // Create a timestamped sub directory for this export session.
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Path timestampDir = exportRoot.resolve(timestamp);

        if (!Files.exists(timestampDir)) {
            Files.createDirectories(timestampDir);
        }

        return timestampDir;
    }
}
