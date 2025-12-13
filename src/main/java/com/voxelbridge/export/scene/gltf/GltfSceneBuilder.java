package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.TimeLogger;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Collects mesh data grouped per Material Group (Block) and writes glTF.
 * Implements deferred primitive generation to support atlas pagination.
 */
public final class GltfSceneBuilder implements SceneSink {
    private final ExportContext ctx;
    private final Path texturesDir;
    private final TextureRegistry textureRegistry;
    
    // Thread-local buffers to avoid global locks during sampling
    private final List<List<GltfQuadRecord>> allBuffers = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<List<GltfQuadRecord>> threadLocalBuffer = ThreadLocal.withInitial(() -> {
        List<GltfQuadRecord> list = new ArrayList<>();
        allBuffers.add(list);
        return list;
    });

    public GltfSceneBuilder(ExportContext ctx, Path outDir) {
        this.ctx = ctx;
        this.texturesDir = outDir.resolve("textures");
        this.textureRegistry = new TextureRegistry(ctx, outDir);
    }

    private record GltfQuadRecord(String materialGroupKey, String spriteKey, String overlaySpriteKey,
                                 float[] positions, float[] uv0, float[] uv1,
                                 float[] normal, float[] colors, boolean doubleSided) {}

    @Override
    public void addQuad(String materialGroupKey,
                        String spriteKey,
                        String overlaySpriteKey,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] normal,
                        float[] colors,
                        boolean doubleSided) {
        if (materialGroupKey == null || spriteKey == null) return;

        // Register texture for atlas generation
        boolean isBlockEntityTexture = spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:") || spriteKey.startsWith("base:");
        if (!isBlockEntityTexture) {
            TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
        }

        // 标准化null uv1为空数组，保持存储一致性
        float[] normalizedUv1 = (uv1 != null) ? uv1 : new float[8];
        threadLocalBuffer.get().add(new GltfQuadRecord(materialGroupKey, spriteKey, overlaySpriteKey, positions, uv0, normalizedUv1, normal, colors, doubleSided));
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        TimeLogger.logMemory("before_quad_processing");

        // OPTIMIZATION: Build primitiveMap directly from thread-local buffers
        // instead of merging all quads first (saves 2-3GB memory spike)
        Map<String, PrimitiveData> primitiveMap = buildPrimitiveMapStreaming();

        TimeLogger.logMemory("after_quad_processing");

        try {
            return writeInternal(request, primitiveMap);
        } finally {
            clearBuffers();
        }
    }

    /**
     * OPTIMIZATION: Stream quads from thread-local buffers directly into primitiveMap
     * instead of merging into one giant list first.
     */
    private Map<String, PrimitiveData> buildPrimitiveMapStreaming() {
        Map<String, PrimitiveData> primitiveMap = new LinkedHashMap<>();

        synchronized (allBuffers) {
            int totalBuffers = allBuffers.size();
            int processedBuffers = 0;

            for (List<GltfQuadRecord> buffer : allBuffers) {
                // Process quads from this buffer
                for (GltfQuadRecord q : buffer) {
                    String spriteKey = q.spriteKey;
                    boolean animated = ExportRuntimeConfig.isAnimationEnabled() &&
                            (ctx.getTextureRepository().hasAnimation(spriteKey) ||
                             (q.overlaySpriteKey != null && ctx.getTextureRepository().hasAnimation(q.overlaySpriteKey)));

                    String primitiveKey = q.materialGroupKey;
                    if (animated) {
                        primitiveKey = safe(q.materialGroupKey);
                        if ("overlay".equals(q.overlaySpriteKey)) {
                            primitiveKey = primitiveKey + "_overlay";
                        }
                        primitiveKey = primitiveKey + "_animated";
                    } else {
                        if ("overlay".equals(q.overlaySpriteKey)) {
                            primitiveKey = q.materialGroupKey + "_overlay";
                        }
                    }

                    if (animated) {
                        String animatedSpriteKey = spriteKey;
                        if (q.overlaySpriteKey != null && ctx.getTextureRepository().hasAnimation(q.overlaySpriteKey)) {
                            animatedSpriteKey = q.overlaySpriteKey;
                        }
                        primitiveKey = safe(animatedSpriteKey);
                        if ("overlay".equals(q.overlaySpriteKey)) {
                            primitiveKey = primitiveKey + "_overlay";
                        }
                        primitiveKey = primitiveKey + "_animated";
                    }

                    PrimitiveData data = primitiveMap.computeIfAbsent(primitiveKey, k -> new PrimitiveData(q.materialGroupKey));
                    String overlaySpriteKey = q.overlaySpriteKey;
                    int[] verts = data.registerQuad(spriteKey, overlaySpriteKey, q.positions, q.uv0, q.uv1, q.colors);
                    if (verts != null) {
                        data.doubleSided |= q.doubleSided;
                        data.addTriangle(verts[0], verts[1], verts[2]);
                        data.addTriangle(verts[0], verts[2], verts[3]);
                    }
                }

                // Clear buffer immediately to free memory
                buffer.clear();
                processedBuffers++;

                if (processedBuffers % 20 == 0) {
                    TimeLogger.logMemory("quad_buffer_" + processedBuffers);
                }
            }
        }

        return primitiveMap;
    }

    private List<GltfQuadRecord> collectAllQuads() {
        List<GltfQuadRecord> merged = new ArrayList<>();
        synchronized (allBuffers) {
            for (List<GltfQuadRecord> list : allBuffers) {
                merged.addAll(list);
            }
        }
        return merged;
    }

    private static String safe(String key) {
        return key == null ? "unknown" : key.replace(':', '_').replace('/', '_');
    }

    private void clearBuffers() {
        synchronized (allBuffers) {
            for (List<GltfQuadRecord> list : allBuffers) {
                list.clear();
            }
            allBuffers.clear();
        }
        threadLocalBuffer.remove();
    }

    private Path writeInternal(SceneWriteRequest request, Map<String, PrimitiveData> primitiveMap) throws IOException {
        // 1. Generate Atlases first so we know UV mappings and Pages
        ColorMapManager.generateColorMaps(ctx, request.outputDir());

        // 2. PrimitiveMap already built in buildPrimitiveMapStreaming()
        // Skip the quad-to-primitive conversion (lines 113-163 in old code)

        // 3. Build GLTF Structure
        GlTF gltf = new GlTF();
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("VoxelBridge");
        gltf.setAsset(asset);

        Path binPath = request.outputDir().resolve(request.baseName() + ".bin");
        Buffer buffer = new Buffer();
        buffer.setUri(binPath.getFileName().toString());
        gltf.addBuffers(buffer);

        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        List<Sampler> samplers = new ArrayList<>();
        BinaryChunk chunk = new BinaryChunk(binPath);
        try {
        
        // Setup Sampler
        Sampler sampler = new Sampler();
        sampler.setMagFilter(9728); // NEAREST
        sampler.setMinFilter(9728); // NEAREST
        sampler.setWrapS(10497);
        sampler.setWrapT(10497);
        samplers.add(sampler);
        gltf.setSamplers(samplers);

        // Register Colormap Textures
        List<Integer> colorMapIndices = registerColorMapTextures(request.outputDir(), textures, images, 0);

        // 并行处理 raw -> 编码片段，按原顺序写入，减少主线程长时间阻塞
        List<String> allKeys = new ArrayList<>(primitiveMap.keySet());
        ExecutorService exec = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        List<Future<EncodedPrimitive>> futures = new ArrayList<>();
        for (int i = 0; i < allKeys.size(); i++) {
            final int order = i;
            final String primitiveKey = allKeys.get(i);
            final PrimitiveData data = primitiveMap.get(primitiveKey);
            futures.add(exec.submit(() -> encodePrimitive(primitiveKey, data, order)));
        }
        exec.shutdown();

        TimeLogger.logMemory("before_primitive_processing");

        int processedCount = 0;
        for (Future<EncodedPrimitive> f : futures) {
            EncodedPrimitive ep;
            try {
                ep = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while encoding primitives", e);
            } catch (ExecutionException e) {
                throw new IOException("Failed to encode primitive", e.getCause());
            }
            if (ep == null || ep.vertexCount == 0 || ep.spriteRanges.isEmpty()) continue;

            int posOffset = chunk.writeFloatArray(ep.positions, ep.positions.length);
            int posView = addView(gltf, 0, posOffset, ep.positions.length * 4, 34962);
            int posAcc = addAccessor(gltf, posView, ep.vertexCount, "VEC3", 5126, ep.positionMin, ep.positionMax);

            int texAcc = -1;
            if (ep.uv0.length > 0) {
                int off = chunk.writeFloatArray(ep.uv0, ep.uv0.length);
                int view = addView(gltf, 0, off, ep.uv0.length * 4, 34962);
                texAcc = addAccessor(gltf, view, ep.vertexCount, "VEC2", 5126, null, null);
            }

            int uv1Acc = -1;
            if (ep.hasNonZeroUV1) {
                int off = chunk.writeFloatArray(ep.uv1, ep.uv1.length);
                int view = addView(gltf, 0, off, ep.uv1.length * 4, 34962);
                uv1Acc = addAccessor(gltf, view, ep.vertexCount, "VEC2", 5126, null, null);
            }

            int colorAcc = -1;
            if (ep.hasColors) {
                int off = chunk.writeFloatArray(ep.colors, ep.colors.length);
                int view = addView(gltf, 0, off, ep.colors.length * 4, 34962);
                colorAcc = addAccessor(gltf, view, ep.vertexCount, "VEC4", 5126, null, null);
            }

            int idxOffset = chunk.writeIntArray(ep.indices, ep.indices.length);
            int idxView = addView(gltf, 0, idxOffset, ep.indices.length * 4, 34963);
            int idxAcc = addAccessor(gltf, idxView, ep.indices.length, "SCALAR", 5125, null, null);

            String sampleSprite = ep.spriteRanges.get(0).spriteKey();
            int textureIndex = textureRegistry.ensureSpriteTexture(sampleSprite, textures, images);
            
            Material material = new Material();
            material.setName(ep.materialGroupKey);
            MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
            TextureInfo texInfo = new TextureInfo();
            texInfo.setIndex(textureIndex);
            pbr.setBaseColorTexture(texInfo);
            pbr.setMetallicFactor(0.0f);
            pbr.setRoughnessFactor(1.0f);
            material.setPbrMetallicRoughness(pbr);
            material.setDoubleSided(ep.doubleSided);

            Map<String, Object> extras = new HashMap<>();
            if (!colorMapIndices.isEmpty()) {
                extras.put("voxelbridge:colormapTextures", colorMapIndices);
                extras.put("voxelbridge:colormapUV", 1);
            }
            if (!extras.isEmpty()) material.setExtras(extras);

            materials.add(material);
            int matIndex = materials.size() - 1;

            MeshPrimitive prim = new MeshPrimitive();
            Map<String, Integer> attrs = new LinkedHashMap<>();
            attrs.put("POSITION", posAcc);
            if (texAcc >= 0) attrs.put("TEXCOORD_0", texAcc);
            if (uv1Acc >= 0) attrs.put("TEXCOORD_1", uv1Acc);
            if (colorAcc >= 0) attrs.put("COLOR_0", colorAcc);
            prim.setAttributes(attrs);
            prim.setIndices(idxAcc);
            prim.setMaterial(matIndex);
            prim.setMode(4);

            Mesh mesh = new Mesh();
            mesh.setName(ep.materialGroupKey);
            mesh.setPrimitives(Collections.singletonList(prim));
            meshes.add(mesh);

            Node node = new Node();
            node.setName(ep.materialGroupKey);
            node.setMesh(meshes.size() - 1);
            nodes.add(node);

            processedCount++;
            primitiveMap.remove(ep.materialGroupKey);
        }

        TimeLogger.logMemory("after_primitive_processing");
        System.out.println("[VoxelBridge] Processed " + processedCount + " primitives");

        if (meshes.isEmpty()) throw new IOException("No geometry generated.");
        
        gltf.setMeshes(meshes);
        gltf.setNodes(nodes);
        gltf.setMaterials(materials);
        gltf.setTextures(textures);
        gltf.setImages(images);

        Scene scene = new Scene();
        List<Integer> nodeIndices = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) nodeIndices.add(i);
        scene.setNodes(nodeIndices);
        gltf.addScenes(scene);
        gltf.setScene(0);

        buffer.setByteLength(Math.toIntExact(chunk.size()));

        GltfAsset assetModel = new GltfAssetV2(gltf, null);
        GltfAssetWriter writer = new GltfAssetWriter();
        writer.writeJson(assetModel, request.outputDir().resolve(request.baseName() + ".gltf").toFile());
        
        return request.outputDir().resolve(request.baseName() + ".gltf");
        } finally {
            chunk.close();
            // Help GC: clear large collections once we're done
            primitiveMap.clear();
            materials.clear();
            meshes.clear();
            nodes.clear();
            textures.clear();
            images.clear();
        }
    }

    private record EncodedPrimitive(
            String materialGroupKey,
            List<PrimitiveData.SpriteRange> spriteRanges,
            float[] positions,
            float[] uv0,
            float[] uv1,
            float[] colors,
            int[] indices,
            int vertexCount,
            boolean doubleSided,
            float[] positionMin,
            float[] positionMax,
            boolean hasNonZeroUV1,
            boolean hasColors,
            int order
    ) {}

    private float[] remapUV(String spriteKey, float u, float v) {
        boolean isBlockEntity = spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:") || spriteKey.startsWith("base:");
        if (isBlockEntity) {
            // Prefer unified atlas placement; fallback to legacy block-entity map if missing
            float[] atlasUv = TextureAtlasManager.remapUV(ctx, spriteKey, 0xFFFFFF, u, v);
            if (ctx.getAtlasBook().containsKey(spriteKey) || atlasUv[0] != u || atlasUv[1] != v) {
                return atlasUv;
            }

            ExportContext.BlockEntityAtlasPlacement p = ctx.getBlockEntityAtlasPlacements().get(spriteKey);

            // If not found, try alternate prefix formats
            if (p == null && spriteKey.startsWith("entity:")) {
                String altKey = "blockentity:" + spriteKey.substring("entity:".length());
                p = ctx.getBlockEntityAtlasPlacements().get(altKey);
            } else if (p == null && spriteKey.startsWith("blockentity:")) {
                String altKey = "entity:" + spriteKey.substring("blockentity:".length());
                p = ctx.getBlockEntityAtlasPlacements().get(altKey);
            }

            if (p != null) {
                // Successfully found placement - remap to atlas space
                return new float[] {
                    p.u0() + (float) ((double) u * (p.u1() - p.u0())),
                    p.v0() + (float) ((double) v * (p.v1() - p.v0()))
                };
            } else {
                // Placement not found - log warning and return original UV
                ExportLogger.log(String.format(
                    "[RemapUV][WARN] No BlockEntity atlas placement found for '%s' - UV will remain in sprite space (0-1). " +
                    "This may indicate the texture was not packed into the atlas.",
                    spriteKey));
                return new float[]{u, v};
            }
        }

        // Regular block texture - use normal atlas remapping
        return TextureAtlasManager.remapUV(ctx, spriteKey, 0xFFFFFF, u, v);
    }
    
    // Helper methods (addView, addAccessor, registerColorMapTextures) same as before...
    private int addView(GlTF gltf, int bufferIndex, int byteOffset, int byteLength, int target) {
        BufferView view = new BufferView();
        view.setBuffer(bufferIndex);
        view.setByteOffset(byteOffset);
        view.setByteLength(byteLength);
        view.setTarget(target);
        gltf.addBufferViews(view);
        return gltf.getBufferViews().size() - 1;
    }

    private int addAccessor(GlTF gltf, int bufferView, int count, String type, int componentType, float[] min, float[] max) {
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

    private Number[] toNumbers(float[] values) {
        Number[] arr = new Number[values.length];
        for (int i = 0; i < values.length; i++) arr[i] = values[i];
        return arr;
    }

    private EncodedPrimitive encodePrimitive(String primitiveKey, PrimitiveData data, int order) {
        if (data == null) return null;
        if (data.vertexCount == 0 || data.spriteRanges.isEmpty()) {
            clearPrimitiveData(data);
            return new EncodedPrimitive(primitiveKey, List.of(), new float[0], new float[0], new float[0], new float[0], new int[0], 0, data.doubleSided, new float[]{0,0,0}, new float[]{0,0,0}, false, false, order);
        }

        float[] positions = Arrays.copyOf(data.positions.getArrayDirect(), data.positions.size());
        float[] uv0 = Arrays.copyOf(data.uv0.getArrayDirect(), data.uv0.size());
        float[] uv1 = Arrays.copyOf(data.uv1.getArrayDirect(), data.uv1.size());
        float[] colors = Arrays.copyOf(data.colors.getArrayDirect(), data.colors.size());
        int[] indices = Arrays.copyOf(data.indices.getArrayDirect(), data.indices.size());
        List<PrimitiveData.SpriteRange> ranges = new ArrayList<>(data.spriteRanges);

        if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS) {
            for (PrimitiveData.SpriteRange range : ranges) {
                boolean animated = ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(range.spriteKey());
                if (animated) continue;
                for (int j = 0; j < range.count(); j++) {
                    int vIdx = range.startVertexIndex() + j;
                    float u = uv0[vIdx * 2];
                    float v = uv0[vIdx * 2 + 1];
                    float[] remapped = remapUV(range.spriteKey(), u, v);
                    uv0[vIdx * 2] = remapped[0];
                    uv0[vIdx * 2 + 1] = remapped[1];

                    if (range.overlaySpriteKey() != null && uv1.length >= (vIdx * 2 + 2)) {
                        float[] overlayRemap = remapUV(range.overlaySpriteKey(), u, v);
                        uv1[vIdx * 2] = overlayRemap[0];
                        uv1[vIdx * 2 + 1] = overlayRemap[1];
                    }
                }
            }
        }

        boolean hasUV1 = hasNonZeroUV1(uv1);
        boolean hasCols = colors.length > 0;
        float[] posMin = data.positionMin();
        float[] posMax = data.positionMax();
        boolean doubleSided = data.doubleSided;

        clearPrimitiveData(data);

        return new EncodedPrimitive(primitiveKey, ranges, positions, uv0, uv1, colors, indices, data.vertexCount, doubleSided, posMin, posMax, hasUV1, hasCols, order);
    }
    
    private List<Integer> registerColorMapTextures(Path outDir, List<Texture> textures, List<Image> images, int samplerIndex) throws IOException {
         Path dir = outDir.resolve("textures/colormap");
         if (!Files.exists(dir)) return Collections.emptyList();
         List<Path> pages;
         try (var stream = Files.list(dir)) {
             pages = stream.filter(p -> p.getFileName().toString().startsWith("colormap_")).sorted().toList();
         }
         List<Integer> indices = new ArrayList<>();
         for (Path png : pages) {
             Image image = new Image();
             image.setUri("textures/colormap/" + png.getFileName().toString());
             images.add(image);
             Texture texture = new Texture();
             texture.setSource(images.size() - 1);
             texture.setSampler(samplerIndex);
             textures.add(texture);
             indices.add(textures.size() - 1);
         }
         return indices;
    }

    /**
     * 检查UV1数据是否包含任何非零值。
     */
    private boolean hasNonZeroUV1(float[] uvs) {
        for (float v : uvs) {
            if (Math.abs(v) > 1e-6f) return true;
        }
        return false;
    }

    /**
     * 检查颜色数据是否包含任何非白色值。
     */
    private boolean hasNonWhiteColors(PrimitiveData data) {
        float[] cols = data.colors.toArray();
        for (int i = 0; i < cols.length; i += 4) {
            float r = cols[i];
            float g = cols[i + 1];
            float b = cols[i + 2];
            float a = cols[i + 3];
            if (Math.abs(r - 1.0f) > 1e-3f ||
                Math.abs(g - 1.0f) > 1e-3f ||
                Math.abs(b - 1.0f) > 1e-3f ||
                Math.abs(a - 1.0f) > 1e-3f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clears PrimitiveData to release memory after writing to glTF buffer.
     * This is safe because Atlas UV remapping is complete and data has been written.
     * Designed to be compatible with future streaming architectures.
     */
    private void clearPrimitiveData(PrimitiveData data) {
        data.positions.clear();
        data.uv0.clear();
        data.uv1.clear();
        data.colors.clear();
        data.indices.clear();
        data.vertexLookup.clear();  // Releases the large HashMap
        data.quadKeys.clear();
        data.spriteRanges.clear();
    }

}
