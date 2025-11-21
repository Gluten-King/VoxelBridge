package com.voxelbridge.export.scene;

import java.io.IOException;

/**
 * Format-agnostic streaming sink for scene geometry.
 */
public interface SceneSink {

    void addQuad(String spriteKey,
                 float[] positions,
                 float[] uv0,
                 float[] uv1,
                 float[] uv2,
                 float[] uv3,
                 float[] normal,
                 float[] colors,
                 boolean doubleSided);

    /**
     * Finalize the scene and write it to disk.
     *
     * @return the primary output file (e.g., glTF/OBJ)
     */
    java.nio.file.Path write(SceneWriteRequest request) throws IOException;
}
