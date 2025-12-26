package com.voxelbridge.export.scene;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parameters passed to a scene sink when writing to disk.
 */
public record SceneWriteRequest(String baseName, Path outputDir) {
    public SceneWriteRequest(String baseName, Path outputDir) {
        this.baseName = Objects.requireNonNull(baseName, "baseName");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
    }
}
