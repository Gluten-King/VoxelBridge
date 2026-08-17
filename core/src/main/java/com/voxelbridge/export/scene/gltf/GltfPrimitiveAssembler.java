package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.ir.IrFlags;
import com.voxelbridge.core.ir.RenderLayer;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.texture.TexturePathResolver;
import com.voxelbridge.export.texture.UvMapper;
import com.voxelbridge.export.texture.UvRemapUtil;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Material;
import de.javagl.jgltf.impl.v2.Mesh;
import de.javagl.jgltf.impl.v2.MeshPrimitive;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.impl.v2.Texture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads one spooled material and appends its accessors, material, mesh, and node. */
final class GltfPrimitiveAssembler {
    private static final int QUAD_RECORD_BYTES = 204;
    private static final int QUAD_HEADER_BYTES = 16;

    private final ExportState state;
    private final ExportOptions options;
    private final SpriteIndex spriteIndex;
    private final GltfMaterialWriter materialWriter;

    GltfPrimitiveAssembler(ExportState state,
                           ExportOptions options,
                           SpriteIndex spriteIndex,
                           GltfMaterialWriter materialWriter) {
        this.state = state;
        this.options = options;
        this.spriteIndex = spriteIndex;
        this.materialWriter = materialWriter;
    }

    void assemble(String materialKey,
                  GeometryIndex.MaterialChunk materialChunk,
                  BinarySpoolReader reader,
                  GltfAccessorWriter accessorWriter,
                  MultiBinaryChunk geometryOutput,
                  MultiBinaryChunk uvOutput,
                  List<Material> materials,
                  List<Mesh> meshes,
                  List<Node> nodes,
                  List<Texture> textures,
                  List<Image> images,
                  List<Integer> colorMapIndices) throws IOException {
        if (materialChunk == null || materialChunk.quadCount() == 0) return;

        int quadCount = materialChunk.quadCount();
        float[] positions = new float[quadCount * 12];
        float[] uv0 = new float[quadCount * 8];
        float[] uv1 = new float[quadCount * 8];
        float[] colors = new float[quadCount * 16];
        int[] indices = new int[quadCount * 6];
        int positionCursor = 0;
        int uv0Cursor = 0;
        int uv1Cursor = 0;
        int colorCursor = 0;
        int indexCursor = 0;
        int vertexBase = 0;
        boolean doubleSided = false;
        RenderLayer strongestLayer = RenderLayer.UNKNOWN;
        int materialHash = materialKey.hashCode();
        int mismatches = 0;
        boolean atlasEnabled = UvRemapUtil.isAtlasEnabled(options);
        boolean colormapMode = UvRemapUtil.isColormapMode(options);
        ByteBuffer pageBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        float[] quadUv0 = new float[8];
        float[] quadUv1 = new float[8];

        for (GeometryIndex.PageInfo page : materialChunk.pages()) {
            int pageBytes = page.quadCount() * QUAD_RECORD_BYTES;
            if (pageBytes > pageBuffer.capacity()) {
                pageBuffer = ByteBuffer.allocateDirect(pageBytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            pageBuffer.clear();
            pageBuffer.limit(pageBytes);
            reader.read(page.byteOffset(), pageBuffer);
            pageBuffer.flip();

            for (int quad = 0; quad < page.quadCount(); quad++) {
                int actualMaterialHash = pageBuffer.getInt();
                int spriteId = pageBuffer.getInt();
                int overlaySpriteId = pageBuffer.getInt();
                int flags = pageBuffer.getInt();
                if (actualMaterialHash != materialHash) {
                    pageBuffer.position(pageBuffer.position() + QUAD_RECORD_BYTES - QUAD_HEADER_BYTES);
                    mismatches++;
                    continue;
                }

                for (int index = 0; index < 12; index++) positions[positionCursor++] = pageBuffer.getFloat();
                pageBuffer.position(pageBuffer.position() + 12); // normals are not written yet
                for (int index = 0; index < 16; index++) colors[colorCursor++] = pageBuffer.getFloat();
                for (int index = 0; index < 8; index++) quadUv0[index] = pageBuffer.getFloat();
                for (int index = 0; index < 8; index++) quadUv1[index] = pageBuffer.getFloat();

                if (atlasEnabled) {
                    remap(spriteIndex.getKey(spriteId), quadUv0);
                    String overlay = overlaySpriteId >= 0 ? spriteIndex.getKey(overlaySpriteId) : null;
                    if (!colormapMode && hasValues(quadUv1) && UvRemapUtil.shouldRemap(state, overlay, options)) {
                        remap(overlay, quadUv1);
                    }
                }
                for (float value : quadUv0) uv0[uv0Cursor++] = value;
                for (float value : quadUv1) uv1[uv1Cursor++] = value;

                indices[indexCursor++] = vertexBase;
                indices[indexCursor++] = vertexBase + 1;
                indices[indexCursor++] = vertexBase + 2;
                indices[indexCursor++] = vertexBase;
                indices[indexCursor++] = vertexBase + 2;
                indices[indexCursor++] = vertexBase + 3;
                vertexBase += 4;
                doubleSided |= IrFlags.isDoubleSided(flags);
                strongestLayer = GltfMaterialWriter.strongerLayer(
                    strongestLayer, IrFlags.decodeRenderLayer(flags));
            }
        }

        if (mismatches > 0) {
            VoxelBridgeLogger.warn(LogModule.GLTF, String.format(
                "[GltfBuilder][WARN] Skipped %d quads for material %s due to hash mismatch",
                mismatches, materialKey));
        }
        if (positionCursor == 0 || indexCursor == 0) return;
        if (positionCursor < positions.length) {
            positions = Arrays.copyOf(positions, positionCursor);
            uv0 = Arrays.copyOf(uv0, uv0Cursor);
            uv1 = Arrays.copyOf(uv1, uv1Cursor);
            colors = Arrays.copyOf(colors, colorCursor);
            indices = Arrays.copyOf(indices, indexCursor);
        }

        float[] minimum = GltfAccessorWriter.min(positions, 3);
        float[] maximum = GltfAccessorWriter.max(positions, 3);
        if (!finite(minimum) || !finite(maximum)) {
            VoxelBridgeLogger.error(LogModule.GLTF,
                "[GltfBuilder][ERROR] Non-finite position bounds; skipping material " + materialKey);
            return;
        }

        int vertexCount = positions.length / 3;
        MultiBinaryChunk.Slice positionSlice = geometryOutput.writeFloatArray(positions, positions.length);
        int positionView = accessorWriter.addView(
            positionSlice.bufferIndex(), positionSlice.byteOffset(), positions.length * 4, 34962);
        int positionAccessor = accessorWriter.addAccessor(
            positionView, vertexCount, "VEC3", 5126, minimum, maximum);

        MultiBinaryChunk.Slice uv0Slice = uvOutput.writeFloatArray(uv0, uv0.length);
        int uv0View = accessorWriter.addView(
            uv0Slice.bufferIndex(), uv0Slice.byteOffset(), uv0.length * 4, 34962);
        int uv0Accessor = accessorWriter.addAccessor(uv0View, vertexCount, "VEC2", 5126, null, null);

        int uv1Accessor = -1;
        boolean hasUv1 = hasValues(uv1);
        if (hasUv1) {
            MultiBinaryChunk.Slice uv1Slice = uvOutput.writeFloatArray(uv1, uv1.length);
            int uv1View = accessorWriter.addView(
                uv1Slice.bufferIndex(), uv1Slice.byteOffset(), uv1.length * 4, 34962);
            uv1Accessor = accessorWriter.addAccessor(uv1View, vertexCount, "VEC2", 5126, null, null);
        }

        MultiBinaryChunk.Slice colorSlice = geometryOutput.writeFloatArray(colors, colors.length);
        int colorView = accessorWriter.addView(
            colorSlice.bufferIndex(), colorSlice.byteOffset(), colors.length * 4, 34962);
        int colorAccessor = accessorWriter.addAccessor(colorView, vertexCount, "VEC4", 5126, null, null);

        MultiBinaryChunk.Slice indexSlice = geometryOutput.writeIntArray(indices, indices.length);
        int indexView = accessorWriter.addView(
            indexSlice.bufferIndex(), indexSlice.byteOffset(), indices.length * 4, 34963);
        int indexAccessor = accessorWriter.addAccessor(
            indexView, indices.length, "SCALAR", 5125, null, null);

        String primarySprite = pickPrimarySprite(materialKey, materialChunk.usedSprites());
        int materialIndex = materialWriter.write(
            materialKey, primarySprite, doubleSided, strongestLayer,
            colorMapIndices, materials, textures, images);

        MeshPrimitive primitive = new MeshPrimitive();
        Map<String, Integer> attributes = new LinkedHashMap<>();
        attributes.put("POSITION", positionAccessor);
        attributes.put("TEXCOORD_0", uv0Accessor);
        if (hasUv1) attributes.put("TEXCOORD_1", uv1Accessor);
        attributes.put("COLOR_0", colorAccessor);
        primitive.setAttributes(attributes);
        primitive.setIndices(indexAccessor);
        primitive.setMaterial(materialIndex);
        primitive.setMode(4);

        Mesh mesh = new Mesh();
        mesh.setName(materialKey);
        mesh.setPrimitives(Collections.singletonList(primitive));
        meshes.add(mesh);
        Node node = new Node();
        node.setName(materialKey);
        node.setMesh(meshes.size() - 1);
        nodes.add(node);
    }

    private void remap(String spriteKey, float[] uvs) {
        if (!UvRemapUtil.shouldRemap(state, spriteKey, options)) return;
        for (int vertex = 0; vertex < 4; vertex++) {
            float[] mapped = UvMapper.remapUv(
                state, spriteKey, uvs[vertex * 2], uvs[vertex * 2 + 1], options);
            uvs[vertex * 2] = mapped[0];
            uvs[vertex * 2 + 1] = mapped[1];
        }
    }

    private String pickPrimarySprite(String materialKey, Set<String> usedSprites) {
        if (usedSprites == null || usedSprites.isEmpty()) return null;
        List<String> withPaths = new ArrayList<>();
        for (String sprite : usedSprites) {
            if (sprite != null && state.getMaterialPaths().containsKey(sprite)) withPaths.add(sprite);
        }
        List<String> candidates = withPaths.isEmpty() ? new ArrayList<>(usedSprites) : withPaths;
        if (materialKey != null && materialKey.endsWith("_animated")) {
            for (String sprite : candidates) {
                if (materialKey.equals(TexturePathResolver.animationBaseName(sprite))) return sprite;
            }
        }
        List<String> sorted = new ArrayList<>(candidates);
        sorted.remove("voxelbridge:transparent");
        if (sorted.isEmpty()) sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        for (String sprite : sorted) if (sprite.contains("item_frame")) return sprite;
        for (String sprite : sorted) if (sprite.startsWith("entity:")) return sprite;
        return sorted.get(0);
    }

    private static boolean hasValues(float[] values) {
        for (float value : values) if (value != 0f) return true;
        return false;
    }

    private static boolean finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }
}
