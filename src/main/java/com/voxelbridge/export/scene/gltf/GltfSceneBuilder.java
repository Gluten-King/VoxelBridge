package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.ExportLogger;
import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.Asset;
import de.javagl.jgltf.impl.v2.Buffer;
import de.javagl.jgltf.impl.v2.BufferView;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Material;
import de.javagl.jgltf.impl.v2.MaterialPbrMetallicRoughness;
import de.javagl.jgltf.impl.v2.Mesh;
import de.javagl.jgltf.impl.v2.MeshPrimitive;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.impl.v2.Sampler;
import de.javagl.jgltf.impl.v2.Scene;
import de.javagl.jgltf.impl.v2.Texture;
import de.javagl.jgltf.impl.v2.TextureInfo;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import com.voxelbridge.config.ExportRuntimeConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects mesh data grouped per material and writes glTF.
 */
public final class GltfSceneBuilder implements SceneSink {
    private final Map<String, PrimitiveData> primitives = new LinkedHashMap<>();
    private final TextureRegistry textureRegistry;
    private final ExportContext ctx;
    private final Object lock = new Object();
    private List<Integer> colorMapTextureIndices;

    public GltfSceneBuilder(ExportContext ctx, Path outDir) {
        this.ctx = ctx;
        this.textureRegistry = new TextureRegistry(ctx, outDir);
    }

    @Override
    public void addQuad(String spriteKey,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] uv2,
                        float[] uv3,
                        float[] normal,
                        float[] colors,
                        boolean doubleSided) {
        if (spriteKey == null || spriteKey.isEmpty()) {
            System.err.println("[GLTF][ERROR] Invalid sprite key: " + spriteKey);
            return;
        }

        // BlockEntity and entity textures remain separate and do not participate in atlas packing.
        boolean isBlockEntityTexture = spriteKey.startsWith("blockentity:")
                || spriteKey.startsWith("entity:")
                || spriteKey.startsWith("base:");
        if (!isBlockEntityTexture) {
            TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
        }

        synchronized (lock) {
            PrimitiveData data = primitives.computeIfAbsent(spriteKey, k -> new PrimitiveData(spriteKey));
            
            // Record overlay sprite key for UV2 remapping
            if (uv2 != null && data.overlaySpriteKey == null) {
                String overlayKey = ctx.getOverlayMappings().get(spriteKey);
                if (overlayKey != null) {
                    data.overlaySpriteKey = overlayKey;
                    ExportLogger.log("[GLTF] Recorded overlay mapping: " + spriteKey + " -> " + overlayKey);
                }
            }
            
            int[] verts = data.registerQuad(positions, uv0, uv1, uv2, uv3, colors);
            if (verts == null) {
                return; // duplicate or degenerate
            }
            data.doubleSided |= doubleSided;

            // With vertices reordered CCW, use a fixed diagonal to avoid butterfly faces
            data.addTriangle(verts[0], verts[1], verts[2]);
            data.addTriangle(verts[0], verts[2], verts[3]);
        }
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        synchronized (lock) {
            return writeInternal(request);
        }
    }

    private Path writeInternal(SceneWriteRequest request) throws IOException {
        Path gltfPath = request.outputDir().resolve(request.baseName() + ".gltf");
        Path binPath = request.outputDir().resolve(request.baseName() + ".bin");
        System.out.printf("[GLTF] Writing glTF with %d materials%n", primitives.size());
        ExportLogger.log("[GLTF] Material count: " + primitives.size());

        for (Map.Entry<String, PrimitiveData> entry : primitives.entrySet()) {
            System.out.printf("[GLTF] Material '%s': %d vertices, %d indices%n",
                    entry.getKey(), entry.getValue().vertexCount, entry.getValue().indices.size());
            ExportLogger.log(String.format("[GLTF] Material '%s': vertices=%d indices=%d",
                    entry.getKey(), entry.getValue().vertexCount, entry.getValue().indices.size()));
        }

        GlTF gltf = new GlTF();
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("VoxelBridge");
        gltf.setAsset(asset);

        Buffer buffer = new Buffer();
        buffer.setUri(binPath.getFileName().toString());
        gltf.addBuffers(buffer);

        BinaryChunk chunk = new BinaryChunk();
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();

        Map<String, Integer> textureIndices = new HashMap<>();
        List<Texture> textures = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        List<Sampler> samplers = new ArrayList<>();

        Sampler sampler = new Sampler();
        sampler.setMagFilter(9728); // NEAREST
        sampler.setMinFilter(9728); // NEAREST
        sampler.setWrapS(10497);
        sampler.setWrapT(10497);
        samplers.add(sampler);
        gltf.setSamplers(samplers);

        // Generate colormap textures (UV1 for biome colors, UV3 for overlay colors)
        ColorMapManager.generateColorMaps(ctx, request.outputDir());
        colorMapTextureIndices = registerColorMapTextures(request.outputDir(), textures, images, 0);

        for (PrimitiveData data : primitives.values()) {
            if (data.vertexCount == 0 || data.indices.isEmpty()) continue;

            // Save original UV0 before remapping (needed for overlay UV2 remapping)
            float[] originalUV0 = null;
            if (data.hasUv2() && !data.uv0.isEmpty()) {
                originalUV0 = data.uv0.toArray().clone();
            }

            // Remap UVs to atlas if needed (after atlas generation)
            if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS && !data.uv0.isEmpty()) {
                float[] uvs = data.uv0.toArray();
                float[] remapped = new float[uvs.length];

                // Check if this is a BlockEntity texture
                boolean isBlockEntity = data.spriteKey.startsWith("entity:")
                                     || data.spriteKey.startsWith("blockentity:");

                if (isBlockEntity) {
                    // Use BlockEntity atlas placement for remapping
                    ExportContext.BlockEntityAtlasPlacement placement = ctx.getBlockEntityAtlasPlacements().get(data.spriteKey);
                    if (placement != null) {
                        ExportLogger.log("[GLTF] Remapping BlockEntity UV for " + data.spriteKey +
                            " using placement: page=" + placement.page() + " uv=[" + placement.u0() + "," + placement.v0() +
                            "]->[" + placement.u1() + "," + placement.v1() + "]");
                        for (int i = 0; i < data.vertexCount; i++) {
                            float u = uvs[i * 2];
                            float v = uvs[i * 2 + 1];
                            remapped[i * 2] = placement.u0() + u * (placement.u1() - placement.u0());
                            remapped[i * 2 + 1] = placement.v0() + v * (placement.v1() - placement.v0());
                        }
                    } else {
                        // No placement found, keep original UVs (INDIVIDUAL mode or error)
                        ExportLogger.log("[GLTF] No atlas placement for BlockEntity " + data.spriteKey + ", keeping original UVs");
                        System.arraycopy(uvs, 0, remapped, 0, uvs.length);
                    }
                } else {
                    // Normal block texture - use TextureAtlasManager
                    for (int i = 0; i < data.vertexCount; i++) {
                        float[] uv = TextureAtlasManager.remapUV(ctx, data.spriteKey, 0xFFFFFF,
                                uvs[i * 2], uvs[i * 2 + 1]);
                        remapped[i * 2] = uv[0];
                        remapped[i * 2 + 1] = uv[1];
                    }
                }

                data.uv0.clear();
                data.uv0.addAll(remapped);
            }

            // Remap uv2 (overlay texture UVs) to use ORIGINAL base UV shape + overlay atlas offset
            if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS &&
                data.hasUv2() && data.overlaySpriteKey != null && originalUV0 != null) {
                float[] baseUVs = originalUV0;  // Use ORIGINAL base UV shape (before atlas remapping)
                float[] remapped2 = new float[baseUVs.length];

                // Check if overlay is a BlockEntity texture
                boolean isBlockEntity = data.overlaySpriteKey.startsWith("entity:")
                                     || data.overlaySpriteKey.startsWith("blockentity:");

                if (isBlockEntity) {
                    // Use BlockEntity atlas placement for overlay - apply offset to base UV shape
                    ExportContext.BlockEntityAtlasPlacement placement = ctx.getBlockEntityAtlasPlacements().get(data.overlaySpriteKey);
                    if (placement != null) {
                        ExportLogger.log("[GLTF] Remapping overlay UV2 for " + data.overlaySpriteKey +
                            " using BASE UV shape + overlay atlas offset");
                        for (int i = 0; i < data.vertexCount; i++) {
                            float u = baseUVs[i * 2];
                            float v = baseUVs[i * 2 + 1];
                            remapped2[i * 2] = placement.u0() + u * (placement.u1() - placement.u0());
                            remapped2[i * 2 + 1] = placement.v0() + v * (placement.v1() - placement.v0());
                        }
                    } else {
                        ExportLogger.log("[GLTF] No atlas placement for overlay " + data.overlaySpriteKey + ", using base UVs");
                        System.arraycopy(baseUVs, 0, remapped2, 0, baseUVs.length);
                    }
                } else {
                    // Normal block overlay texture - use base UV shape + overlay atlas offset
                    for (int i = 0; i < data.vertexCount; i++) {
                        float[] uv = TextureAtlasManager.remapUV(ctx, data.overlaySpriteKey, 0xFFFFFF,
                                baseUVs[i * 2], baseUVs[i * 2 + 1]);
                        remapped2[i * 2] = uv[0];
                        remapped2[i * 2 + 1] = uv[1];
                    }
                }

                data.uv2.clear();
                data.uv2.addAll(remapped2);
                ExportLogger.log("[GLTF] Remapped overlay UV2 using base shape for " + data.spriteKey + " overlay=" + data.overlaySpriteKey);
            }

            // UV3 (overlay colormap) does NOT need remapping!
            // The colormap UVs from ColorMapManager.registerColor() are already in UDIM absolute coordinates.
            // No processing needed - just keep the UV3 values as-is from BlockExporter.
            ExportLogger.log("[GLTF] UV3 colormap (no remapping needed) for " + data.spriteKey + 
                " hasUv3=" + data.hasUv3() + " uv3.size=" + data.uv3.size());

            System.out.printf("[GLTF][DEBUG] Material '%s': vertexCount=%d, positions.size()=%d, indices.size()=%d%n",
                    data.spriteKey, data.vertexCount, data.positions.size(), data.indices.size());
            ExportLogger.log(String.format("[GLTF][DEBUG] Material '%s': vertexCount=%d, positions=%d, indices=%d",
                    data.spriteKey, data.vertexCount, data.positions.size(), data.indices.size()));

            float[] posArray = data.positions.toArray();
            System.out.printf("[GLTF][DEBUG] posArray.length=%d (expected %d)%n",
                    posArray.length, data.vertexCount * 3);
            ExportLogger.log(String.format("[GLTF][DEBUG] posArray.length=%d expected=%d for %s",
                    posArray.length, data.vertexCount * 3, data.spriteKey));

            if (posArray.length == 0) {
                System.err.println("[GLTF][ERROR] CRITICAL: posArray is EMPTY for material: " + data.spriteKey);
                System.err.println("[GLTF][ERROR] This means positions.toArray() returned empty array!");
                System.err.println("[GLTF][ERROR] positions.size()=" + data.positions.size());
                System.err.println("[GLTF][ERROR] Expected: " + (data.vertexCount * 3) + " floats");
                ExportLogger.log("[GLTF][ERROR] CRITICAL empty posArray for " + data.spriteKey + " size=" + data.positions.size());
                continue;
            }

            int posOffset = chunk.writeFloatArray(posArray);
            System.out.printf("[GLTF][DEBUG] After writing: chunk.size()=%d, posOffset=%d%n", chunk.size(), posOffset);
            ExportLogger.log(String.format("[GLTF][DEBUG] posOffset=%d chunk=%d after %s", posOffset, chunk.size(), data.spriteKey));

            int posView = addView(gltf, 0, posOffset, data.positions.size() * Float.BYTES, 34962);
            int posAccessor = addAccessor(gltf, posView, data.vertexCount, "VEC3", 5126,
                    data.positionMin(), data.positionMax());

            int texAccessor = -1;
            if (!data.uv0.isEmpty()) {
                int uvOffset = chunk.writeFloatArray(data.uv0.toArray());
                int uvView = addView(gltf, 0, uvOffset, data.uv0.size() * Float.BYTES, 34962);
                texAccessor = addAccessor(gltf, uvView, data.vertexCount, "VEC2", 5126,
                        null, null);
            }

            int uv1Accessor = -1;
            if (!data.uv1.isEmpty()) {
                int uv1Offset = chunk.writeFloatArray(data.uv1.toArray());
                int uv1View = addView(gltf, 0, uv1Offset, data.uv1.size() * Float.BYTES, 34962);
                uv1Accessor = addAccessor(gltf, uv1View, data.vertexCount, "VEC2", 5126, null, null);
            }

            int uv2Accessor = -1;
            if (data.hasUv2()) {
                float[] uv2Array = data.uv2.toArray();
                // Ensure uv2 array size matches vertex count (VEC2 = 2 floats per vertex)
                int expectedSize = data.vertexCount * 2;
                if (uv2Array.length != expectedSize) {
                    ExportLogger.log("[GLTF][WARNING] uv2 size mismatch for " + data.spriteKey + 
                        ": expected " + expectedSize + ", got " + uv2Array.length);
                    // Pad or truncate to match expected size
                    float[] corrected = new float[expectedSize];
                    System.arraycopy(uv2Array, 0, corrected, 0, Math.min(uv2Array.length, expectedSize));
                    uv2Array = corrected;
                }
                int uv2Offset = chunk.writeFloatArray(uv2Array);
                int uv2View = addView(gltf, 0, uv2Offset, uv2Array.length * Float.BYTES, 34962);
                uv2Accessor = addAccessor(gltf, uv2View, data.vertexCount, "VEC2", 5126, null, null);
            }

            int uv3Accessor = -1;
            if (data.hasUv3()) {
                float[] uv3Array = data.uv3.toArray();
                // Ensure uv3 array size matches vertex count (VEC2 = 2 floats per vertex)
                int expectedSize = data.vertexCount * 2;
                if (uv3Array.length != expectedSize) {
                    ExportLogger.log("[GLTF][WARNING] uv3 size mismatch for " + data.spriteKey + 
                        ": expected " + expectedSize + ", got " + uv3Array.length);
                    // Pad or truncate to match expected size
                    float[] corrected = new float[expectedSize];
                    System.arraycopy(uv3Array, 0, corrected, 0, Math.min(uv3Array.length, expectedSize));
                    uv3Array = corrected;
                }
                int uv3Offset = chunk.writeFloatArray(uv3Array);
                int uv3View = addView(gltf, 0, uv3Offset, uv3Array.length * Float.BYTES, 34962);
                uv3Accessor = addAccessor(gltf, uv3View, data.vertexCount, "VEC2", 5126, null, null);
            }

            // Always use colormap (no vertex colors exported)
            int colorAccessor = -1;

            int idxOffset = chunk.writeIntArray(data.indices.toArray());
            int idxView = addView(gltf, 0, idxOffset, data.indices.size() * Integer.BYTES, 34963);
            int idxAccessor = addAccessor(gltf, idxView, data.indices.size(),
                    "SCALAR", 5125, new float[]{0f}, new float[]{data.maxIndex()});

            int textureIndex = textureIndices.computeIfAbsent(data.spriteKey, key ->
                    textureRegistry.ensureSpriteTexture(key, textures, images));

            Material material = new Material();
            material.setName(data.spriteKey);
            MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
            TextureInfo texInfo = new TextureInfo();
            texInfo.setIndex(textureIndex);
            pbr.setBaseColorTexture(texInfo);
            pbr.setMetallicFactor(0.0f);
            pbr.setRoughnessFactor(1.0f);
            material.setPbrMetallicRoughness(pbr);
            material.setDoubleSided(data.doubleSided);

            Map<String, Object> extras = null;
            if (colorMapTextureIndices != null && !colorMapTextureIndices.isEmpty()) {
                extras = new HashMap<>();
                extras.put("voxelbridge:colormapTextures", colorMapTextureIndices);
                extras.put("voxelbridge:colormapUV", 1);
            }

            // Add overlay texture if present (TEXCOORD_2)
            if (uv2Accessor >= 0) {
                String overlaySpriteKey = data.spriteKey + "_overlay";
                int overlayTextureIndex = textureIndices.computeIfAbsent(overlaySpriteKey, key ->
                        textureRegistry.ensureSpriteTexture(key, textures, images));

                if (extras == null) {
                    extras = new HashMap<>();
                }
                extras.put("voxelbridge:overlayTexture", overlayTextureIndex);
                extras.put("voxelbridge:overlayUV", 2);
            }

            if (extras != null) {
                material.setExtras(extras);
            }

            materials.add(material);
            int materialIndex = materials.size() - 1;

            MeshPrimitive primitive = new MeshPrimitive();
            Map<String, Integer> attributes = new LinkedHashMap<>();
            attributes.put("POSITION", posAccessor);
            if (texAccessor >= 0) attributes.put("TEXCOORD_0", texAccessor);
            if (uv1Accessor >= 0) attributes.put("TEXCOORD_1", uv1Accessor);
            if (uv2Accessor >= 0) attributes.put("TEXCOORD_2", uv2Accessor);
            if (uv3Accessor >= 0) attributes.put("TEXCOORD_3", uv3Accessor);
            if (colorAccessor >= 0) attributes.put("COLOR_0", colorAccessor);
            primitive.setAttributes(attributes);
            primitive.setIndices(idxAccessor);
            primitive.setMaterial(materialIndex);
            primitive.setMode(4);
            Mesh mesh = new Mesh();
            mesh.setName(data.spriteKey);
            mesh.setPrimitives(Collections.singletonList(primitive));
            meshes.add(mesh);

            Node node = new Node();
            node.setName(data.spriteKey);
            node.setMesh(meshes.size() - 1);
            nodes.add(node);

            System.out.printf(
                    "[GLTF][DEBUG] Primitive built: indices=%d posOffset=%d uv0Offset=%s colorOffset=%s idxOffset=%d chunkSize=%d%n",
                    data.indices.size(),
                    posOffset,
                    (texAccessor >= 0 ? "set" : "none"),
                    (colorAccessor >= 0 ? "set" : "none"),
                    idxOffset,
                    chunk.size());
            ExportLogger.log(String.format(
                    "[GLTF][DEBUG] Primitive built: indices=%d posOffset=%d uv0=%s color=%s idxOffset=%d chunk=%d material=%s",
                    data.indices.size(), posOffset,
                    (texAccessor >= 0 ? "set" : "none"),
                    (colorAccessor >= 0 ? "set" : "none"),
                    idxOffset, chunk.size(), data.spriteKey));
        }

        if (meshes.isEmpty()) {
            ExportLogger.log("[GLTF][WARN] No primitives generated.");
        }
        gltf.setMeshes(meshes);
        gltf.setNodes(nodes);

        Scene scene = new Scene();
        List<Integer> nodeIndices = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            nodeIndices.add(i);
        }
        scene.setNodes(nodeIndices);
        gltf.addScenes(scene);
        gltf.setScene(0);

        gltf.setMaterials(materials);
        gltf.setTextures(textures);
        gltf.setImages(images);

        byte[] bytes = chunk.toByteArray();
        System.out.printf("[GLTF][DEBUG] Final chunk size: %d bytes, meshPrimitives: %d%n", bytes.length, meshes.size());

        if (meshes.isEmpty()) {
            throw new IOException("[VoxelBridge][GLTF] No geometry generated - no mesh primitives created. Check if region contains any visible blocks.");
        }
        int chunkSize = bytes.length;
        if (chunkSize <= 0) {
            throw new IOException("[VoxelBridge][GLTF] No geometry data written to buffer. Primitive count: " + meshes.size() + ", chunkSize=" + chunkSize);
        }
        ExportLogger.log(String.format("[GLTF][DEBUG] Pre-set buffer: chunkSize=%d, primitives=%d", chunkSize, meshes.size()));
        buffer.setByteLength(chunkSize);

        ExportLogger.log(String.format("[GLTF][DEBUG] buffer.byteLength=%d, binaryChunkSize=%d, primitives=%d", buffer.getByteLength(), chunkSize, meshes.size()));
        System.out.printf("[GLTF][DEBUG] buffer.byteLength=%d, binaryChunkSize=%d, primitives=%d%n", buffer.getByteLength(), chunkSize, meshes.size());

        Files.write(binPath, bytes);
        // Provide binary data to GltfAsset so writer validations see the actual buffer length.
        GltfAsset assetModel = new GltfAssetV2(gltf, ByteBuffer.wrap(bytes));
        GltfAssetWriter writer = new GltfAssetWriter();
        writer.writeJson(assetModel, gltfPath.toFile());
        return gltfPath;
    }

    /**
     * Registers generated colormap pages (textures/colormap/colormap_*.png) as glTF images/textures.
     */
    private List<Integer> registerColorMapTextures(Path outDir,
                                                   List<Texture> textures,
                                                   List<Image> images,
                                                   int samplerIndex) throws IOException {
        Path dir = outDir.resolve("textures/colormap");
        if (!Files.exists(dir)) {
            ExportLogger.log("[GLTF][WARN] Colormap directory not found: " + dir);
            return Collections.emptyList();
        }

        List<Path> pages;
        try (var stream = Files.list(dir)) {
            pages = stream
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("colormap_") && name.endsWith(".png");
                    })
                    .sorted(Comparator.comparingInt(this::parseUdim))
                    .toList();
        }

        List<Integer> indices = new ArrayList<>();
        for (Path png : pages) {
            String uri = "textures/colormap/" + png.getFileName();
            Image image = new Image();
            image.setUri(uri.replace('\\', '/'));
            images.add(image);

            Texture texture = new Texture();
            texture.setSource(images.size() - 1);
            texture.setSampler(samplerIndex);
            textures.add(texture);

            indices.add(textures.size() - 1);
            ExportLogger.log("[GLTF][COLORMAP] Registered " + uri + " as texture index " + (textures.size() - 1));
        }
        return indices;
    }

    private static int addView(GlTF gltf, int bufferIndex, int byteOffset, int byteLength, int target) {
        BufferView view = new BufferView();
        view.setBuffer(bufferIndex);
        view.setByteOffset(byteOffset);
        view.setByteLength(byteLength);
        view.setTarget(target);
        gltf.addBufferViews(view);
        return gltf.getBufferViews().size() - 1;
    }

    private static int addAccessor(GlTF gltf,
                                   int bufferView,
                                   int count,
                                   String type,
                                   int componentType,
                                   float[] min,
                                   float[] max) {
        Accessor accessor = new Accessor();
        accessor.setBufferView(bufferView);
        accessor.setComponentType(componentType);
        accessor.setCount(count);
        accessor.setType(type);
        if (min != null) accessor.setMin(toNumbers(min));
        if (max != null) accessor.setMax(toNumbers(max));
        gltf.addAccessors(accessor);
        return gltf.getAccessors().size() - 1;
    }

    private static Number[] toNumbers(float[] values) {
        Number[] arr = new Number[values.length];
        for (int i = 0; i < values.length; i++) {
            arr[i] = values[i];
        }
        return arr;
    }

    private int parseUdim(Path path) {
        String name = path.getFileName().toString();
        int underscore = name.indexOf('_');
        int dot = name.lastIndexOf('.');
        if (underscore >= 0 && dot > underscore) {
            try {
                return Integer.parseInt(name.substring(underscore + 1, dot));
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }
}
