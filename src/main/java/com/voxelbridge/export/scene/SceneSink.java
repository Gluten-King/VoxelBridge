package com.voxelbridge.export.scene;

import java.io.IOException;

/**
 * Format-agnostic streaming sink for scene geometry.
 */
public interface SceneSink {

    /**
     * Adds a quad to the scene.
     *
     * @param materialGroupKey The key used to group geometry into meshes (e.g. "minecraft:glass").
     * All quads with the same materialGroupKey will be merged into one Mesh
     * (unless split by atlas pages).
     * @param spriteKey        The texture sprite key used for UV mapping (e.g. "minecraft:block/glass").
     * Used to look up the correct UV region in the Atlas.
     * @param overlaySpriteKey The overlay texture sprite key (or "voxelbridge:transparent" / null if no overlay).
     * @param positions        Vertex positions (x, y, z) * 4
     * @param uv0              Primary texture coordinates (TEXCOORD_0: u, v) * 4
     * @param uv1              Colormap coordinates for biome tinting (TEXCOORD_1: u, v) * 4
     * @param normal           Face normal (x, y, z)
     * @param colors           Vertex colors (r, g, b, a) * 4
     * @param doubleSided      Whether the face should be rendered double-sided
     */
    void addQuad(String materialGroupKey,
                 String spriteKey,
                 String overlaySpriteKey,
                 float[] positions,
                 float[] uv0,
                 float[] uv1,
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
