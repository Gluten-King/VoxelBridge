package com.voxelbridge.export.scene;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parameters passed to a scene sink when writing to disk.
 */
public record SceneWriteRequest(String baseName, Path outputDir, Path outputPath) {
    public SceneWriteRequest(String baseName, Path outputDir) {
        this(baseName, outputDir, null);
    }

    public SceneWriteRequest(String baseName, Path outputDir, Path outputPath) {
        // Allow callers to specify either a directory + base name or a fully-qualified path.
        if (outputPath == null) {
            baseName = Objects.requireNonNull(baseName, "baseName");
            outputDir = Objects.requireNonNull(outputDir, "outputDir");
            outputPath = outputDir.resolve(baseName + ".gltf");
        } else {
            if (outputDir == null) {
                outputDir = outputPath.getParent() != null ? outputPath.getParent() : Path.of(".");
            }
            if (baseName == null) {
                String fileName = outputPath.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
            }
        }

        this.baseName = Objects.requireNonNull(baseName, "baseName");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
    }
}
