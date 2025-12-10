package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.ExportLogger;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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
        List<GltfQuadRecord> quads = collectAllQuads();
        try {
            return writeInternal(request, quads);
        } finally {
            clearBuffers();
        }
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

    private void clearBuffers() {
        synchronized (allBuffers) {
            for (List<GltfQuadRecord> list : allBuffers) {
                list.clear();
            }
            allBuffers.clear();
        }
        threadLocalBuffer.remove();
    }

    private Path writeInternal(SceneWriteRequest request, List<GltfQuadRecord> quads) throws IOException {
        // 1. Generate Atlases first so we know UV mappings and Pages
        ColorMapManager.generateColorMaps(ctx, request.outputDir());
        // Note: Main atlas generation happens in GltfExportService, explicitly called BEFORE sceneSink.write()
        // But to be safe, we assume atlases are ready or will be ready. 
        // Actually, GltfExportService calls generateAllAtlases before write(). Correct.

        Map<String, PrimitiveData> primitiveMap = new LinkedHashMap<>();

        // 2. Sort quads into Primitives based on MaterialGroup only (ignore atlas pages)
        for (GltfQuadRecord q : quads) {
            String spriteKey = q.spriteKey;

            // Create a unique key for the GLTF Primitive
            // Base quads: "minecraft:glass"
            // Overlay quads: "minecraft:glass_overlay" (all overlays merged)
            String primitiveKey = q.materialGroupKey;
            if ("overlay".equals(q.overlaySpriteKey)) {
                primitiveKey = q.materialGroupKey + "_overlay";
            }

            PrimitiveData data = primitiveMap.computeIfAbsent(primitiveKey, k -> new PrimitiveData(q.materialGroupKey));
            
            // Use overlay sprite key from quad record
            String overlaySpriteKey = q.overlaySpriteKey;

            int[] verts = data.registerQuad(spriteKey, overlaySpriteKey, q.positions, q.uv0, q.uv1, q.colors);
            if (verts != null) {
                data.doubleSided |= q.doubleSided;
                data.addTriangle(verts[0], verts[1], verts[2]);
                data.addTriangle(verts[0], verts[2], verts[3]);
            }
        }

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

        BinaryChunk chunk = new BinaryChunk();
        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        List<Texture> textures = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        List<Sampler> samplers = new ArrayList<>();
        
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

        // Map to track created textures/materials to avoid duplication
        // Key: primitiveKey (e.g. "glass#0"), Value: Material Index
        Map<String, Integer> materialIndices = new HashMap<>();

        for (Map.Entry<String, PrimitiveData> entry : primitiveMap.entrySet()) {
            String primitiveKey = entry.getKey(); // "glass#0"
            PrimitiveData data = entry.getValue();
            
            if (data.vertexCount == 0) continue;
            if (data.spriteRanges.isEmpty()) {
                ExportLogger.log(String.format("[GLTF][WARN] primitive '%s' has no sprite ranges; skipping to avoid invalid UV remap", primitiveKey));
                continue;
            }

            // 4. Remap UVs using SpriteRanges
            if (ExportRuntimeConfig.getAtlasMode() == ExportRuntimeConfig.AtlasMode.ATLAS) {
                float[] uvs = data.uv0.toArray();

                for (PrimitiveData.SpriteRange range : data.spriteRanges) {
                    boolean animated = ExportRuntimeConfig.isAnimationEnabled() &&
                        ctx.getTextureRepository().hasAnimation(range.spriteKey());
                    if (animated) {
                        continue; // keep sprite-space UVs for animated textures
                    }
                    for (int i = 0; i < range.count(); i++) {
                        int vIdx = range.startVertexIndex() + i;
                        float u = uvs[vIdx * 2];
                        float v = uvs[vIdx * 2 + 1];
                        
                        // Remap base UV
                        float[] newUV = remapUV(range.spriteKey(), u, v);
                        uvs[vIdx * 2] = newUV[0];
                        uvs[vIdx * 2 + 1] = newUV[1];
                    }
                }
                data.uv0.clear();
                data.uv0.addAll(uvs);

            }

            // 5. Write Buffers and Accessors
            int posOffset = chunk.writeFloatArray(data.positions.toArray());
            int posView = addView(gltf, 0, posOffset, data.positions.size() * 4, 34962);
            int posAcc = addAccessor(gltf, posView, data.vertexCount, "VEC3", 5126, data.positionMin(), data.positionMax());

            int texAcc = -1;
            if (!data.uv0.isEmpty()) {
                int off = chunk.writeFloatArray(data.uv0.toArray());
                int view = addView(gltf, 0, off, data.uv0.size() * 4, 34962);
                texAcc = addAccessor(gltf, view, data.vertexCount, "VEC2", 5126, null, null);
            }
            
            // 只在UV1数据存在且非全零时创建TEXCOORD_1访问器
            int uv1Acc = -1;
            if (!data.uv1.isEmpty() && hasNonZeroUV1(data)) {
                int off = chunk.writeFloatArray(data.uv1.toArray());
                int view = addView(gltf, 0, off, data.uv1.size() * 4, 34962);
                uv1Acc = addAccessor(gltf, view, data.vertexCount, "VEC2", 5126, null, null);
            }

            // 始终写入 COLOR_0，保持顶点颜色存在（无 tint 则为纯白）
            int colorAcc = -1;
            if (!data.colors.isEmpty()) {
                int off = chunk.writeFloatArray(data.colors.toArray());
                int view = addView(gltf, 0, off, data.colors.size() * 4, 34962);
                colorAcc = addAccessor(gltf, view, data.vertexCount, "VEC4", 5126, null, null);
            }

            int idxOffset = chunk.writeIntArray(data.indices.toArray());
            int idxView = addView(gltf, 0, idxOffset, data.indices.size() * 4, 34963);
            int idxAcc = addAccessor(gltf, idxView, data.indices.size(), "SCALAR", 5125, null, null);

            // 6. Create Material
            // We use the first sprite in the primitive to determine the texture (Page).
            // Since we grouped by Page, all sprites in this primitive should share the same Page/Texture.
            String sampleSprite = data.spriteRanges.get(0).spriteKey();
            int textureIndex = textureRegistry.ensureSpriteTexture(sampleSprite, textures, images);
            
            Material material = new Material();
            material.setName(data.materialGroupKey); // "minecraft:glass"
            MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
            TextureInfo texInfo = new TextureInfo();
            texInfo.setIndex(textureIndex);
            pbr.setBaseColorTexture(texInfo);
            pbr.setMetallicFactor(0.0f);
            pbr.setRoughnessFactor(1.0f);
            material.setPbrMetallicRoughness(pbr);
            material.setDoubleSided(data.doubleSided);

            // Extensions for Colormap / Overlay
            Map<String, Object> extras = new HashMap<>();
            if (!colorMapIndices.isEmpty()) {
                extras.put("voxelbridge:colormapTextures", colorMapIndices);
                extras.put("voxelbridge:colormapUV", 1);
            }
            if (!extras.isEmpty()) material.setExtras(extras);

            materials.add(material);
            int matIndex = materials.size() - 1;

            // 7. Create Mesh Primitive
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
            mesh.setName(primitiveKey);
            mesh.setPrimitives(Collections.singletonList(prim));
            meshes.add(mesh);

            Node node = new Node();
            node.setName(primitiveKey);
            node.setMesh(meshes.size() - 1);
            nodes.add(node);
        }

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

        byte[] bytes = chunk.toByteArray();
        buffer.setByteLength(bytes.length);
        Files.write(binPath, bytes);
        
        GltfAsset assetModel = new GltfAssetV2(gltf, ByteBuffer.wrap(bytes));
        GltfAssetWriter writer = new GltfAssetWriter();
        writer.writeJson(assetModel, request.outputDir().resolve(request.baseName() + ".gltf").toFile());
        
        return request.outputDir().resolve(request.baseName() + ".gltf");
    }

    private float[] remapUV(String spriteKey, float u, float v) {
        // Check if this is a block entity texture
        if (spriteKey.startsWith("blockentity:") || spriteKey.startsWith("entity:") || spriteKey.startsWith("base:")) {
            // Try direct lookup first
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
                    p.u0() + u * (p.u1() - p.u0()),
                    p.v0() + v * (p.v1() - p.v0())
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
    private boolean hasNonZeroUV1(PrimitiveData data) {
        float[] uvs = data.uv1.toArray();
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
}
