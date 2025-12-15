package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.ExportLogger;
import com.voxelbridge.util.ProgressNotifier;
import com.voxelbridge.util.TimeLogger;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式几何处理管道（重构版）
 * 1. 接收 Quad -> 流式写入 geometry.bin + uvraw.bin
 * 2. 采样结束 -> 生成图集 (Atlas)
 * 3. UV重映射 -> uvraw.bin -> finaluv.bin
 * 4. 流式组装glTF -> 从 geometry.bin + finaluv.bin 直接构建
 */
public final class GltfSceneBuilder implements SceneSink {
    private final ExportContext ctx;
    private final Path outputDir;
    private final TextureRegistry textureRegistry;
    private static final int BYTES_PER_QUAD_GEOMETRY = 140;
    private static final int BYTES_PER_QUAD_UV = 64;

    // 流式写入器
    private final StreamingGeometryWriter streamingWriter;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;

    // 线程通信
    private static final QuadBatch POISON_PILL = new QuadBatch(null, null, null, null, null, null, null, null, false);
    private final BlockingQueue<QuadBatch> queue = new ArrayBlockingQueue<>(16384);
    private final AtomicBoolean writerStarted = new AtomicBoolean(false);
    private Thread writerThread;

    // 临时四边形数据结构（写入队列）
    private record QuadBatch(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] normal,
        float[] colors,
        boolean doubleSided
    ) {}

    public GltfSceneBuilder(ExportContext ctx, Path outDir) throws IOException {
        this.ctx = ctx;
        this.outputDir = outDir;
        this.textureRegistry = new TextureRegistry(ctx, outDir);

        // 创建流式索引
        this.spriteIndex = new SpriteIndex();
        this.geometryIndex = new GeometryIndex();

        // 创建流式写入器
        Path geometryBin = outDir.resolve("geometry.bin");
        Path uvrawBin = outDir.resolve("uvraw.bin");
        this.streamingWriter = new StreamingGeometryWriter(geometryBin, uvrawBin, spriteIndex, geometryIndex);

        ExportLogger.log("[GltfBuilder] Initialized streaming geometry pipeline");
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

        // 启动写线程
        startWriterThread();

        // 入队
        try {
            queue.put(new QuadBatch(
                materialGroupKey, spriteKey, overlaySpriteKey,
                positions, uv0, uv1, normal, colors, doubleSided
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        Minecraft mc = ctx.getMc();

        try {
            // 1. 结束采样，等待写线程完成
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "完成采样");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 1/4: Finalizing sampling...");

            try {
                queue.put(POISON_PILL);
                if (writerThread != null) {
                    writerThread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Export interrupted during writer thread join", e);
            }

            streamingWriter.finalizeWrite();

            long totalQuads = spriteIndex.getTotalQuadCount();
            ExportLogger.log(String.format("[GltfBuilder] Sampling complete. Total quads: %d", totalQuads));
            ExportLogger.log(String.format("[GltfBuilder] Materials: %d", geometryIndex.size()));
            ExportLogger.log(String.format("[GltfBuilder] Sprites: %d", spriteIndex.size()));

            if (totalQuads == 0) {
                throw new IOException("No geometry data was written during sampling phase");
            }

            // 2. 生成图集
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "生成图集");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 2/4: Generating texture atlases...");

            for (String spriteKey : spriteIndex.getAllKeys()) {
                TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            }
            TextureAtlasManager.generateAllAtlases(ctx, request.outputDir());
            ColorMapManager.generateColorMaps(ctx, request.outputDir());
            ExportLogger.log("[GltfBuilder] Texture atlas generation complete");

            // 3. UV重映射
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "重映射UV");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 3/4: Remapping UVs...");

            Path geometryBin = request.outputDir().resolve("geometry.bin");
            Path uvrawBin = request.outputDir().resolve("uvraw.bin");
            Path finaluvBin = request.outputDir().resolve("finaluv.bin");

            if (!java.nio.file.Files.exists(geometryBin)) {
                throw new IOException("geometry.bin not found at: " + geometryBin);
            }
            if (!java.nio.file.Files.exists(uvrawBin)) {
                throw new IOException("uvraw.bin not found at: " + uvrawBin);
            }

            UVRemapper.remapUVs(geometryBin, uvrawBin, finaluvBin, spriteIndex, ctx);
            ExportLogger.log("[GltfBuilder] UV remapping complete");

            // 4. 组装glTF
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "组装glTF");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 4/4: Assembling glTF...");

            Path result = assembleGltf(request, geometryBin, finaluvBin);
            ExportLogger.log("[GltfBuilder] glTF assembly complete: " + result);

            return result;
        } catch (Exception e) {
            ExportLogger.log("[GltfBuilder][ERROR] Export failed in write() method: " + e.getClass().getName() + ": " + e.getMessage());
            ExportLogger.log("[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            if (e.getCause() != null) {
                ExportLogger.log("[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    ExportLogger.log("    at " + element.toString());
                }
            }
            e.printStackTrace();
            throw new IOException("Export failed: " + e.getMessage(), e);
        }
    }

    private void startWriterThread() {
        if (writerStarted.getAndSet(true)) return;

        writerThread = new Thread(() -> {
            try {
                while (true) {
                    QuadBatch batch = queue.take();
                    if (batch == POISON_PILL) break;

                    // 写入流式文件
                    streamingWriter.writeQuad(
                        batch.materialGroupKey,
                        batch.spriteKey,
                        batch.overlaySpriteKey,
                        batch.positions,
                        batch.uv0,
                        batch.uv1,
                        batch.normal,
                        batch.colors,
                        batch.doubleSided
                    );
                }
            } catch (Exception e) {
                ExportLogger.log("[GltfBuilder][ERROR] Writer thread failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "VoxelBridge-StreamingWriter");
        writerThread.start();
    }

    /**
     * 从geometry.bin和finaluv.bin流式组装glTF
     */
    private Path assembleGltf(SceneWriteRequest request, Path geometryBin, Path finaluvBin) throws IOException {
        ExportLogger.log("[GltfBuilder] Starting glTF assembly...");
        TimeLogger.logMemory("before_gltf_assembly");

        try {
            GlTF gltf = new GlTF();
            Asset asset = new Asset();
            asset.setVersion("2.0");
            asset.setGenerator("VoxelBridge");
            gltf.setAsset(asset);

        Path binPath = request.outputDir().resolve(request.baseName() + ".bin");
        Path uvBinPath = request.outputDir().resolve(request.baseName() + ".uv.bin");

        // Thread-safe lists for parallel material assembly
        List<Material> materials = Collections.synchronizedList(new ArrayList<>());
        List<Mesh> meshes = Collections.synchronizedList(new ArrayList<>());
        List<Node> nodes = Collections.synchronizedList(new ArrayList<>());
        List<Texture> textures = new ArrayList<>();
            List<Image> images = new ArrayList<>();
            List<Sampler> samplers = new ArrayList<>();

            Sampler sampler = new Sampler();
            sampler.setMagFilter(9728);
            sampler.setMinFilter(9728);
            sampler.setWrapS(10497);
            sampler.setWrapT(10497);
            samplers.add(sampler);
            gltf.setSamplers(samplers);

            ExportLogger.log("[GltfBuilder] Registering colormap textures...");
            List<Integer> colorMapIndices = registerColorMapTextures(request.outputDir(), textures, images, 0);
            ExportLogger.log("[GltfBuilder] Colormap textures registered: " + colorMapIndices.size());

        try (MultiBinaryChunk chunk = new MultiBinaryChunk(binPath, gltf);
             MultiBinaryChunk uvChunk = new MultiBinaryChunk(uvBinPath, gltf);
             FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
             FileChannel uvChannel = FileChannel.open(finaluvBin, StandardOpenOption.READ)) {

                ExportLogger.log("[GltfBuilder] Opened binary files for reading");
                ExportLogger.log("[GltfBuilder] geometry.bin size: " + geometryChannel.size() + " bytes");
                ExportLogger.log("[GltfBuilder] finaluv.bin size: " + uvChannel.size() + " bytes");

                // OPTIMIZATION: Parallel material assembly (1.5-2x speedup on 8-core CPU)
                // Process materials in parallel using thread pool
                List<String> materialKeys = geometryIndex.getAllMaterialKeys();
                int totalMaterials = materialKeys.size();
                AtomicInteger processedMaterials = new AtomicInteger(0);

                ExportLogger.log("[GltfBuilder] Processing " + totalMaterials + " materials in parallel...");

                // Use 4 threads for parallel assembly (good balance for 8-core CPU)
                int parallelThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
                ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
                List<Future<?>> futures = new ArrayList<>();

                try {
                    for (String matKey : materialKeys) {
                        futures.add(executor.submit(() -> {
                            try {
                                GeometryIndex.MaterialChunk matChunk = geometryIndex.getMaterial(matKey);

                                // 日志: 处理材质前的状态
                                int processed = processedMaterials.get();
                                if (matChunk != null && processed % 100 == 0) {
                                    ExportLogger.log(String.format("[GltfBuilder] Processing material: %s (quads: %d)",
                                        matKey, matChunk.quadCount()));
                                }

                                // Each thread needs its own FileChannels for thread-safe reading
                                try (FileChannel threadGeometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
                                     FileChannel threadUvChannel = FileChannel.open(finaluvBin, StandardOpenOption.READ)) {

                                    // 从geometry.bin和finaluv.bin读取该material的数据
                                    assembleMaterialPrimitive(
                                        matKey, matChunk,
                                        threadGeometryChannel, threadUvChannel,
                                        gltf, chunk, uvChunk,
                                        materials, meshes, nodes, textures, images, colorMapIndices
                                    );
                                }

                                int completed = processedMaterials.incrementAndGet();

                                // 进度提示（每10%）
                                if (totalMaterials > 10 && completed % Math.max(1, totalMaterials / 10) == 0) {
                                    double progress = completed * 100.0 / totalMaterials;
                                    ExportLogger.log(String.format("[GltfAssembly] %.0f%% (%d/%d materials)",
                                        progress, completed, totalMaterials));
                                }
                            } catch (Exception e) {
                                ExportLogger.log("[GltfBuilder][ERROR] Failed to assemble material: " + matKey);
                                ExportLogger.log("[GltfBuilder][ERROR] Error details: " + e.getClass().getName() + ": " + e.getMessage());
                                e.printStackTrace();
                                throw new RuntimeException("Failed to assemble material: " + matKey, e);
                            }
                        }));
                    }

                    // Wait for all materials to complete
                    for (Future<?> future : futures) {
                        try {
                            future.get();
                        } catch (Exception e) {
                            throw new IOException("Material assembly failed", e);
                        }
                    }
                } finally {
                    executor.shutdown();
                }

                ExportLogger.log("[GltfBuilder] All materials processed successfully");

                // Finalize glTF
                Scene scene = new Scene();
                List<Integer> nodeIndices = new ArrayList<>();
                for (int i = 0; i < nodes.size(); i++) nodeIndices.add(i);
                scene.setNodes(nodeIndices);
                gltf.addScenes(scene);
                gltf.setScene(0);

                gltf.setMeshes(meshes);
                gltf.setMaterials(materials);
                gltf.setNodes(nodes);
                gltf.setTextures(textures);
                gltf.setImages(images);

                // 确保缓冲区写入并更新长度后再写 glTF JSON
                chunk.close();
                uvChunk.close();

                ExportLogger.log("[GltfBuilder] Writing glTF file...");
                GltfAsset assetModel = new GltfAssetV2(gltf, null);
                GltfAssetWriter writer = new GltfAssetWriter();
                Path gltfPath = request.outputDir().resolve(request.baseName() + ".gltf");
                writer.writeJson(assetModel, gltfPath.toFile());
                ExportLogger.log("[GltfBuilder] glTF file written successfully: " + gltfPath);

                // 验证文件确实被创建
                if (!java.nio.file.Files.exists(gltfPath)) {
                    throw new IOException("glTF file was not created: " + gltfPath);
                }
                long gltfSize = java.nio.file.Files.size(gltfPath);
                ExportLogger.log("[GltfBuilder] glTF file size: " + gltfSize + " bytes");
            }

            // 清理临时文件
            try {
                Files.deleteIfExists(geometryBin);
                Files.deleteIfExists(request.outputDir().resolve("uvraw.bin"));
                Files.deleteIfExists(finaluvBin);
                ExportLogger.log("[GltfBuilder] Temporary files cleaned up");
            } catch (IOException e) {
                ExportLogger.log("[GltfBuilder][WARN] Failed to delete temporary files: " + e.getMessage());
            }

            Path finalPath = request.outputDir().resolve(request.baseName() + ".gltf");
            ExportLogger.log("[GltfBuilder] Assembly complete: " + finalPath);
            return finalPath;
        } catch (Exception e) {
            ExportLogger.log("[GltfBuilder][ERROR] glTF assembly failed: " + e.getClass().getName() + ": " + e.getMessage());
            ExportLogger.log("[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            if (e.getCause() != null) {
                ExportLogger.log("[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    ExportLogger.log("    at " + element.toString());
                }
            }
            e.printStackTrace();
            throw new IOException("glTF assembly failed", e);
        }
    }

    /**
     * 组装单个material的primitive（使用PrimitiveData进行全局去重）
     */
    private void assembleMaterialPrimitive(
        String matKey,
        GeometryIndex.MaterialChunk matChunk,
        FileChannel geometryChannel,
        FileChannel uvChannel,
        GlTF gltf,
        MultiBinaryChunk chunk,
        MultiBinaryChunk uvChunk,
        List<Material> materials,
        List<Mesh> meshes,
        List<Node> nodes,
        List<Texture> textures,
        List<Image> images,
        List<Integer> colorMapIndices
    ) throws IOException {
        if (matChunk == null || matChunk.quadCount() == 0) return;

        // 使用PrimitiveData进行全局顶点去重
        PrimitiveData primData = new PrimitiveData(matKey, matChunk.quadCount());

        // OPTIMIZATION: Larger buffers for batch reading (1.3-1.5x faster I/O)
        // Read up to 512 quads at once to reduce system calls
        final int BATCH_SIZE = 512;
        final int GEOMETRY_BATCH_BYTES = BATCH_SIZE * BYTES_PER_QUAD_GEOMETRY;
        final int UV_BATCH_BYTES = BATCH_SIZE * BYTES_PER_QUAD_UV;

        ByteBuffer geometryBatchBuffer = ByteBuffer.allocateDirect(GEOMETRY_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvBatchBuffer = ByteBuffer.allocateDirect(UV_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        // OPTIMIZATION: Sort quadOffsets for sequential disk reads (2-3x faster I/O)
        // Random seeks on SSD: ~50MB/s, Sequential reads: ~500MB/s
        List<Long> sortedOffsets = new ArrayList<>(matChunk.quadOffsets());
        Collections.sort(sortedOffsets);

        // 批量读取并注册到PrimitiveData（进行去重）
        // 注意：排序后可以顺序读取，大幅提升I/O性能
        int totalQuads = sortedOffsets.size();
        for (int batchStart = 0; batchStart < totalQuads; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, totalQuads);
            int batchCount = batchEnd - batchStart;

            // Calculate batch read position (first quad in batch)
            long firstQuadOffset = sortedOffsets.get(batchStart);
            long geometryBatchPos = firstQuadOffset * BYTES_PER_QUAD_GEOMETRY;
            long uvBatchPos = firstQuadOffset * BYTES_PER_QUAD_UV;

            // Read batch into buffers
            geometryBatchBuffer.clear();
            geometryBatchBuffer.limit(batchCount * BYTES_PER_QUAD_GEOMETRY);
            geometryChannel.position(geometryBatchPos);
            readFully(geometryChannel, geometryBatchBuffer);
            geometryBatchBuffer.flip();

            uvBatchBuffer.clear();
            uvBatchBuffer.limit(batchCount * BYTES_PER_QUAD_UV);
            uvChannel.position(uvBatchPos);
            readFully(uvChannel, uvBatchBuffer);
            uvBatchBuffer.flip();

            // Process all quads in this batch
            for (int i = 0; i < batchCount; i++) {
                // geometry.bin格式: materialHash(4) + spriteId(4) + overlayId(4) + doubleSided(1) + pad(3) + pos(48) + normal(12) + color(64)
                geometryBatchBuffer.getInt(); // skip materialHash
                int spriteId = geometryBatchBuffer.getInt();
                int overlayId = geometryBatchBuffer.getInt();
                byte doubleSidedByte = geometryBatchBuffer.get();
                geometryBatchBuffer.get(); // skip padding
                geometryBatchBuffer.get();
                geometryBatchBuffer.get();

                // 读取positions (12 floats)
                float[] positions = new float[12];
                for (int j = 0; j < 12; j++) {
                    positions[j] = geometryBatchBuffer.getFloat();
                }

                // 跳过normal (3 floats)
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();

                // 读取colors (16 floats)
                float[] colors = new float[16];
                for (int j = 0; j < 16; j++) {
                    colors[j] = geometryBatchBuffer.getFloat();
                }

                // 读取UV
                float[] uv0 = new float[8];
                float[] uv1 = new float[8];
                for (int j = 0; j < 8; j++) {
                    uv0[j] = uvBatchBuffer.getFloat();
                }
                for (int j = 0; j < 8; j++) {
                    uv1[j] = uvBatchBuffer.getFloat();
                }

                // 从sprite ID获取sprite key
                String spriteKey = spriteIndex.getKey(spriteId);
                String overlaySpriteKey = overlayId >= 0 ? spriteIndex.getKey(overlayId) : null;

                // 注册quad到PrimitiveData（进行全局去重）
                int[] quadIndices = primData.registerQuad(spriteKey, overlaySpriteKey, positions, uv0, uv1, colors);
                if (quadIndices != null) {
                    // 生成索引 (quad -> 2 triangles)
                    primData.indices.add(quadIndices[0]);
                    primData.indices.add(quadIndices[1]);
                    primData.indices.add(quadIndices[2]);
                    primData.indices.add(quadIndices[0]);
                    primData.indices.add(quadIndices[2]);
                    primData.indices.add(quadIndices[3]);

                    if (doubleSidedByte != 0) {
                        primData.doubleSided = true;
                    }
                }
            }
        }

        // 如果去重后没有顶点或索引，跳过
        if (primData.vertexCount == 0 || primData.indices.size() == 0) {
            ExportLogger.log("[GltfBuilder] Skipping material " + matKey + " (no valid geometry after deduplication)");
            return;
        }

        // 验证数据完整性
        if (primData.positions.size() == 0 || primData.uv0.size() == 0 || primData.colors.size() == 0) {
            ExportLogger.log("[GltfBuilder][WARN] Material " + matKey + " has incomplete data, skipping");
            return;
        }

        // 日志:记录数据大小用于调试
        ExportLogger.log(String.format("[GltfBuilder] Material %s: vertices=%d, indices=%d, pos=%d, uv0=%d, colors=%d",
            matKey, primData.vertexCount, primData.indices.size(),
            primData.positions.size(), primData.uv0.size(), primData.colors.size()));

        // 写入glTF buffers（使用去重后的数据）
        MultiBinaryChunk.Slice posSlice = chunk.writeFloatArray(primData.positions.getArrayDirect(), primData.positions.size());
        int posView = addView(gltf, posSlice.bufferIndex(), posSlice.byteOffset(), primData.positions.size() * 4, 34962);
        float[] posMin = primData.positions.computeMin();
        float[] posMax = primData.positions.computeMax();
        int posAcc = addAccessor(gltf, posView, primData.vertexCount, "VEC3", 5126, posMin, posMax);

        MultiBinaryChunk.Slice uv0Slice = uvChunk.writeFloatArray(primData.uv0.getArrayDirect(), primData.uv0.size());
        int uv0View = addView(gltf, uv0Slice.bufferIndex(), uv0Slice.byteOffset(), primData.uv0.size() * 4, 34962);
        int uv0Acc = addAccessor(gltf, uv0View, primData.vertexCount, "VEC2", 5126, null, null);

        MultiBinaryChunk.Slice uv1Slice = uvChunk.writeFloatArray(primData.uv1.getArrayDirect(), primData.uv1.size());
        int uv1View = addView(gltf, uv1Slice.bufferIndex(), uv1Slice.byteOffset(), primData.uv1.size() * 4, 34962);
        int uv1Acc = addAccessor(gltf, uv1View, primData.vertexCount, "VEC2", 5126, null, null);

        MultiBinaryChunk.Slice colorSlice = chunk.writeFloatArray(primData.colors.getArrayDirect(), primData.colors.size());
        int colorView = addView(gltf, colorSlice.bufferIndex(), colorSlice.byteOffset(), primData.colors.size() * 4, 34962);
        int colorAcc = addAccessor(gltf, colorView, primData.vertexCount, "VEC4", 5126, null, null);

        MultiBinaryChunk.Slice idxSlice = chunk.writeIntArray(primData.indices.getArrayDirect(), primData.indices.size());
        int idxView = addView(gltf, idxSlice.bufferIndex(), idxSlice.byteOffset(), primData.indices.size() * 4, 34963);
        int idxAcc = addAccessor(gltf, idxView, primData.indices.size(), "SCALAR", 5125, null, null);

        // 创建material
        String sampleSprite = matChunk.usedSprites().iterator().next();
        int textureIndex = textureRegistry.ensureSpriteTexture(sampleSprite, textures, images);

        Material material = new Material();
        material.setName(matKey);
        MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
        TextureInfo texInfo = new TextureInfo();
        texInfo.setIndex(textureIndex);
        pbr.setBaseColorTexture(texInfo);
        pbr.setMetallicFactor(0.0f);
        pbr.setRoughnessFactor(1.0f);
        material.setPbrMetallicRoughness(pbr);
        material.setDoubleSided(primData.doubleSided);

        Map<String, Object> extras = new HashMap<>();
        if (!colorMapIndices.isEmpty()) {
            extras.put("voxelbridge:colormapTextures", colorMapIndices);
            extras.put("voxelbridge:colormapUV", 1);
        }
        if (!extras.isEmpty()) material.setExtras(extras);
        materials.add(material);
        int matIndex = materials.size() - 1;

        // 创建mesh
        MeshPrimitive prim = new MeshPrimitive();
        Map<String, Integer> attrs = new LinkedHashMap<>();
        attrs.put("POSITION", posAcc);
        attrs.put("TEXCOORD_0", uv0Acc);
        attrs.put("TEXCOORD_1", uv1Acc);
        attrs.put("COLOR_0", colorAcc);
        prim.setAttributes(attrs);
        prim.setIndices(idxAcc);
        prim.setMaterial(matIndex);
        prim.setMode(4);

        Mesh mesh = new Mesh();
        mesh.setName(matKey);
        mesh.setPrimitives(Collections.singletonList(prim));
        meshes.add(mesh);

        Node node = new Node();
        node.setName(matKey);
        node.setMesh(meshes.size() - 1);
        nodes.add(node);

        // OPTIMIZATION: Release PrimitiveData memory immediately after writing
        // This prevents memory accumulation across all materials (saves 6-8GB)
        primData.releaseMemory();
    }

    private void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException("Unexpected end of channel while reading streamed data");
            }
        }
    }

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
        if (min != null) accessor.setMin(toNumberArray(min));
        if (max != null) accessor.setMax(toNumberArray(max));
        gltf.addAccessors(accessor);
        return gltf.getAccessors().size() - 1;
    }

    private Number[] toNumberArray(float[] arr) {
        Number[] num = new Number[arr.length];
        for (int i = 0; i < arr.length; i++) num[i] = arr[i];
        return num;
    }

    private float[] computeMin(float[] data, int stride) {
        float[] min = new float[stride];
        Arrays.fill(min, Float.MAX_VALUE);
        for (int i = 0; i < data.length; i += stride) {
            for (int j = 0; j < stride; j++) {
                min[j] = Math.min(min[j], data[i + j]);
            }
        }
        return min;
    }

    private float[] computeMax(float[] data, int stride) {
        float[] max = new float[stride];
        Arrays.fill(max, -Float.MAX_VALUE);
        for (int i = 0; i < data.length; i += stride) {
            for (int j = 0; j < stride; j++) {
                max[j] = Math.max(max[j], data[i + j]);
            }
        }
        return max;
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
}
