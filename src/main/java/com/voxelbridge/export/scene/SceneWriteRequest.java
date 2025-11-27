package com.voxelbridge.export.scene;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parameters passed to a scene sink when writing to disk.
 */
public final class SceneWriteRequest {
    private final String baseName;
    private final Path outputDir;

    public SceneWriteRequest(String baseName, Path outputDir) {
        this.baseName = Objects.requireNonNull(baseName, "baseName");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
    }

    public String baseName() {
        return baseName;
    }

    public Path outputDir() {
        return outputDir;
    }
}
