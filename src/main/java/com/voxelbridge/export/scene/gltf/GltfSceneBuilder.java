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
import java.util.concurrent.atomic.AtomicLong;
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
    
    // Thread-local primitive aggregation to avoid large intermediate buffers
    private final List<Map<String, PrimitiveData>> allBuffers = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Map<String, PrimitiveData>> threadLocalBuffer = ThreadLocal.withInitial(() -> {
        Map<String, PrimitiveData> map = new LinkedHashMap<>();
        allBuffers.add(map);
        return map;
    });
    private final AtomicLong quadCounter = new AtomicLong(0);

    public GltfSceneBuilder(ExportContext ctx, Path outDir) {
        this.ctx = ctx;
        this.texturesDir = outDir.resolve("textures");
        this.textureRegistry = new TextureRegistry(ctx, outDir);
    }

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

        // Register overlay sprite if present
        if (overlaySpriteKey != null && !"overlay".equals(overlaySpriteKey)) {
            TextureAtlasManager.registerTint(ctx, overlaySpriteKey, 0xFFFFFF);
        }

        // Normalize UV1: overlay uses UV1 channel (colormap), otherwise UV1 is zeroed
        float[] normalizedUv1 = uv1;
        if (!"overlay".equals(overlaySpriteKey)) {
            normalizedUv1 = new float[uv0.length];
        }

        String primitiveKey = resolvePrimitiveKey(materialGroupKey, spriteKey, overlaySpriteKey);
        Map<String, PrimitiveData> map = threadLocalBuffer.get();
        PrimitiveData data = map.computeIfAbsent(primitiveKey, k -> new PrimitiveData(materialGroupKey));
        int[] verts = data.registerQuad(spriteKey, overlaySpriteKey, positions, uv0, normalizedUv1, colors);
        if (verts != null) {
            data.doubleSided |= doubleSided;
            data.addTriangle(verts[0], verts[1], verts[2]);
            data.addTriangle(verts[0], verts[2], verts[3]);
        }
        quadCounter.incrementAndGet();
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        TimeLogger.logMemory("before_quad_processing");
        long tBuildPrimitive = TimeLogger.now();

        // OPTIMIZATION: Build primitiveMap directly from thread-local buffers
        // instead of merging all quads first (saves 2-3GB memory spike)
        Map<String, PrimitiveData> primitiveMap = buildPrimitiveMapStreaming();
        TimeLogger.logDuration("quad_to_primitive_build", TimeLogger.elapsedSince(tBuildPrimitive));
        TimeLogger.logMemory("after_quad_processing");
        TimeLogger.logStat("primitive_count", primitiveMap.size());
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
            for (Map<String, PrimitiveData> buffer : allBuffers) {
                for (Map.Entry<String, PrimitiveData> entry : buffer.entrySet()) {
                    primitiveMap.merge(entry.getKey(), entry.getValue(), this::mergePrimitiveData);
                }
                buffer.clear();
            }
        }
        TimeLogger.logStat("quad_count", quadCounter.get());

        return primitiveMap;
    }

    private PrimitiveData mergePrimitiveData(PrimitiveData target, PrimitiveData source) {
        int baseVertex = target.vertexCount;
        // positions (vec3)
        float[] srcPos = source.positions.toArray();
        for (float v : srcPos) target.positions.add(v);
        // uv0
        float[] srcUv0 = source.uv0.toArray();
        for (float v : srcUv0) target.uv0.add(v);
        // uv1
        float[] srcUv1 = source.uv1.toArray();
        for (float v : srcUv1) target.uv1.add(v);
        // colors
        float[] srcCol = source.colors.toArray();
        for (float v : srcCol) target.colors.add(v);
        // indices with offset
        int[] srcIdx = source.indices.toArray();
        for (int idx : srcIdx) target.indices.add(idx + baseVertex);
        // sprite ranges
        for (PrimitiveData.SpriteRange range : source.spriteRanges) {
            target.spriteRanges.add(new PrimitiveData.SpriteRange(
                range.startVertexIndex() + baseVertex,
                range.count(),
                range.spriteKey(),
                range.overlaySpriteKey()
            ));
        }
        target.vertexCount += source.vertexCount;
        target.doubleSided |= source.doubleSided;
        return target;
    }

    private String resolvePrimitiveKey(String materialGroupKey, String spriteKey, String overlaySpriteKey) {
        boolean animated = isAnimated(spriteKey, overlaySpriteKey);
        if (animated) {
            String animatedSpriteKey = spriteKey;
            if (overlaySpriteKey != null && ctx.getTextureRepository().hasAnimation(overlaySpriteKey)) {
                animatedSpriteKey = overlaySpriteKey;
            }
            String key = safe(animatedSpriteKey);
            if ("overlay".equals(overlaySpriteKey)) {
                key = key + "_overlay";
            }
            return key + "_animated";
        }
        if ("overlay".equals(overlaySpriteKey)) {
            return materialGroupKey + "_overlay";
        }
        return materialGroupKey;
    }

    private boolean isAnimated(String spriteKey, String overlaySpriteKey) {
        if (!ExportRuntimeConfig.isAnimationEnabled()) {
            return false;
        }
        if (ctx.getTextureRepository().hasAnimation(spriteKey)) {
            return true;
        }
        return overlaySpriteKey != null && ctx.getTextureRepository().hasAnimation(overlaySpriteKey);
    }

    private static String safe(String key) {
        return key == null ? "unknown" : key.replace(':', '_').replace('/', '_');
    }

    private void clearBuffers() {
        synchronized (allBuffers) {
            for (Map<String, PrimitiveData> map : allBuffers) {
                map.clear();
            }
            allBuffers.clear();
        }
        quadCounter.set(0);
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
        int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService exec = Executors.newFixedThreadPool(poolSize);
        TimeLogger.logStat("primitive_thread_pool_size", poolSize);
        List<Future<EncodedPrimitive>> futures = new ArrayList<>();
        for (int i = 0; i < allKeys.size(); i++) {
            final int order = i;
            final String primitiveKey = allKeys.get(i);
            final PrimitiveData data = primitiveMap.get(primitiveKey);
            futures.add(exec.submit(() -> encodePrimitive(primitiveKey, data, order)));
        }
        exec.shutdown();

        TimeLogger.logMemory("before_primitive_processing");
        long tEncodeAndWrite = TimeLogger.now();
        long totalPosBytes = 0;
        long totalUv0Bytes = 0;
        long totalUv1Bytes = 0;
        long totalColorBytes = 0;
        long totalIndexBytes = 0;
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
            if (ep == null || ep.vertexCount() == 0 || ep.spriteRanges().isEmpty()) continue;

            int posOffset = chunk.writeFloatArray(ep.positions(), ep.positionsLength());
            int posView = addView(gltf, 0, posOffset, ep.positionsLength() * 4, 34962);
            int posAcc = addAccessor(gltf, posView, ep.vertexCount(), "VEC3", 5126, ep.positionMin(), ep.positionMax());
            totalPosBytes += (long) ep.positionsLength() * 4;

            int texAcc = -1;
            if (ep.uv0Length() > 0) {
                int off = chunk.writeFloatArray(ep.uv0(), ep.uv0Length());
                int view = addView(gltf, 0, off, ep.uv0Length() * 4, 34962);
                texAcc = addAccessor(gltf, view, ep.vertexCount(), "VEC2", 5126, null, null);
                totalUv0Bytes += (long) ep.uv0Length() * 4;
            }

            int uv1Acc = -1;
            if (ep.hasNonZeroUV1() && ep.uv1Length() > 0) {
                int off = chunk.writeFloatArray(ep.uv1(), ep.uv1Length());
                int view = addView(gltf, 0, off, ep.uv1Length() * 4, 34962);
                uv1Acc = addAccessor(gltf, view, ep.vertexCount(), "VEC2", 5126, null, null);
                totalUv1Bytes += (long) ep.uv1Length() * 4;
            }

            int colorAcc = -1;
            if (ep.hasColors() && ep.colorsLength() > 0) {
                int off = chunk.writeFloatArray(ep.colors(), ep.colorsLength());
                int view = addView(gltf, 0, off, ep.colorsLength() * 4, 34962);
                colorAcc = addAccessor(gltf, view, ep.vertexCount(), "VEC4", 5126, null, null);
                totalColorBytes += (long) ep.colorsLength() * 4;
            }

            int idxOffset = chunk.writeIntArray(ep.indices(), ep.indicesLength());
            int idxView = addView(gltf, 0, idxOffset, ep.indicesLength() * 4, 34963);
            int idxAcc = addAccessor(gltf, idxView, ep.indicesLength(), "SCALAR", 5125, null, null);
            totalIndexBytes += (long) ep.indicesLength() * 4;

            String sampleSprite = ep.spriteRanges().get(0).spriteKey();
            int textureIndex = textureRegistry.ensureSpriteTexture(sampleSprite, textures, images);
            
            Material material = new Material();
            material.setName(ep.materialGroupKey());
            MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
            TextureInfo texInfo = new TextureInfo();
            texInfo.setIndex(textureIndex);
            pbr.setBaseColorTexture(texInfo);
            pbr.setMetallicFactor(0.0f);
            pbr.setRoughnessFactor(1.0f);
            material.setPbrMetallicRoughness(pbr);
            material.setDoubleSided(ep.doubleSided());

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
            mesh.setName(ep.materialGroupKey());
            mesh.setPrimitives(Collections.singletonList(prim));
            meshes.add(mesh);

            Node node = new Node();
            node.setName(ep.materialGroupKey());
            node.setMesh(meshes.size() - 1);
            nodes.add(node);

            processedCount++;
            primitiveMap.remove(ep.materialGroupKey());
        }
        long encodeAndWriteNanos = TimeLogger.elapsedSince(tEncodeAndWrite);
        TimeLogger.logDuration("primitive_encode_and_write", encodeAndWriteNanos);
        TimeLogger.logStat("encoded_primitive_count", processedCount);
        TimeLogger.logSize("positions_bytes", totalPosBytes);
        TimeLogger.logSize("uv0_bytes", totalUv0Bytes);
        TimeLogger.logSize("uv1_bytes", totalUv1Bytes);
        TimeLogger.logSize("color_bytes", totalColorBytes);
        TimeLogger.logSize("index_bytes", totalIndexBytes);
        long totalPayload = totalPosBytes + totalUv0Bytes + totalUv1Bytes + totalColorBytes + totalIndexBytes;
        if (encodeAndWriteNanos > 0) {
            double throughputMbPerSec = (totalPayload / 1024.0 / 1024.0) / (encodeAndWriteNanos / 1_000_000_000.0);
            TimeLogger.logInfo(String.format("binary_write_throughput: %.2f MB/s", throughputMbPerSec));
        }
        TimeLogger.logSize("bin_size_bytes", chunk.size());

        TimeLogger.logMemory("after_primitive_processing");
        System.out.println("[VoxelBridge] Processed " + processedCount + " primitives");

        if (meshes.isEmpty()) throw new IOException("No geometry generated.");
        
        gltf.setMeshes(meshes);
        gltf.setNodes(nodes);
        gltf.setMaterials(materials);
        gltf.setTextures(textures);
        gltf.setImages(images);
        TimeLogger.logStat("mesh_count", meshes.size());
        TimeLogger.logStat("node_count", nodes.size());
        TimeLogger.logStat("material_count", materials.size());
        TimeLogger.logStat("texture_count", textures.size());
        TimeLogger.logStat("image_count", images.size());

        Scene scene = new Scene();
        List<Integer> nodeIndices = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) nodeIndices.add(i);
        scene.setNodes(nodeIndices);
        gltf.addScenes(scene);
        gltf.setScene(0);

        buffer.setByteLength(Math.toIntExact(chunk.size()));

        GltfAsset assetModel = new GltfAssetV2(gltf, null);
        GltfAssetWriter writer = new GltfAssetWriter();
        long tJsonWrite = TimeLogger.now();
        writer.writeJson(assetModel, request.outputDir().resolve(request.baseName() + ".gltf").toFile());
        TimeLogger.logDuration("gltf_json_write", TimeLogger.elapsedSince(tJsonWrite));

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
            int positionsLength,
            float[] uv0,
            int uv0Length,
            float[] uv1,
            int uv1Length,
            float[] colors,
            int colorsLength,
            int[] indices,
            int indicesLength,
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
            return new EncodedPrimitive(
                primitiveKey,
                List.of(),
                new float[0], 0,
                new float[0], 0,
                new float[0], 0,
                new float[0], 0,
                new int[0], 0,
                0,
                data.doubleSided,
                new float[]{0,0,0},
                new float[]{0,0,0},
                false,
                false,
                order
            );
        }

        float[] positions = data.positions.getArrayDirect();
        int positionsLength = data.positions.size();
        float[] uv0 = data.uv0.getArrayDirect();
        int uv0Length = data.uv0.size();
        float[] uv1 = data.uv1.getArrayDirect();
        int uv1Length = data.uv1.size();
        float[] colors = data.colors.getArrayDirect();
        int colorsLength = data.colors.size();
        int[] indices = data.indices.getArrayDirect();
        int indicesLength = data.indices.size();
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

        boolean hasUV1 = hasNonZeroUV1(uv1, uv1Length);
        boolean hasCols = colorsLength > 0;
        float[] posMin = data.positionMin();
        float[] posMax = data.positionMax();
        boolean doubleSided = data.doubleSided;

        clearPrimitiveData(data);

        return new EncodedPrimitive(
            primitiveKey,
            ranges,
            positions,
            positionsLength,
            uv0,
            uv0Length,
            uv1,
            uv1Length,
            colors,
            colorsLength,
            indices,
            indicesLength,
            data.vertexCount,
            doubleSided,
            posMin,
            posMax,
            hasUV1,
            hasCols,
            order
        );
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
    private boolean hasNonZeroUV1(float[] uvs, int length) {
        for (int i = 0; i < length; i++) {
            if (Math.abs(uvs[i]) > 1e-6f) return true;
        }
        return false;
    }

    /**
     * 检查颜色数据是否包含任何非白色值。
     */
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
