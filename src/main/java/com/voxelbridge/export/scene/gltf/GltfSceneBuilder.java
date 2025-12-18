package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.debug.ExportLogger;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.util.debug.TimeLogger;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming geometry processing pipeline (refactored)
 * 1. Receive Quad -> Stream to geometry.bin + uvraw.bin
 * 2. Sampling complete -> Generate atlas
 * 3. UV remapping -> uvraw.bin -> finaluv.bin
 * 4. Assemble glTF -> Build directly from geometry.bin + finaluv.bin
 */
public final class GltfSceneBuilder implements SceneSink {
    private final ExportContext ctx;
    private final Path outputDir;
    private final TextureRegistry textureRegistry;
    private static final int BYTES_PER_QUAD_GEOMETRY = 140;
    private static final int BYTES_PER_QUAD_UV = 64;

    // Streaming writer
    private final StreamingGeometryWriter streamingWriter;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;

    // Thread communication
    private static final QuadBatch POISON_PILL = new QuadBatch(null, null, null, null, null, null, null, null, false);
    private final BlockingQueue<QuadBatch> queue = new ArrayBlockingQueue<>(16384);
    private final AtomicBoolean writerStarted = new AtomicBoolean(false);
    private Thread writerThread;

    // Temporary quad data structure (for queue)
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

        // Create streaming indices
        this.spriteIndex = new SpriteIndex();
        this.geometryIndex = new GeometryIndex();

        // Create streaming writer
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

        // Colormap mode: all quads must have TEXCOORD_1; non-tinted points to reserved white slot
        if (ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP) {
            if (uv1 == null || uv1.length < 8) {
                float[] lut = ColorMapManager.remapColorUV(ctx, 0xFFFFFFFF);
                float u0 = lut[0], v0 = lut[1], u1v = lut[2], v1v = lut[3];
                uv1 = new float[]{
                    u0, v0,
                    u1v, v0,
                    u1v, v1v,
                    u0, v1v
                };
            }
        }

        // Start writer thread
        startWriterThread();

        // Enqueue
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
            long tFinalizeSampling = TimeLogger.now();

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
            TimeLogger.logDuration("gltf_finalize_sampling", TimeLogger.elapsedSince(tFinalizeSampling));

            if (totalQuads == 0) {
                throw new IOException("No geometry data was written during sampling phase");
            }

            // 2. 生成图集
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "生成图集");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 2/4: Generating texture atlases...");
            long tAtlas = TimeLogger.now();

            for (String spriteKey : spriteIndex.getAllKeys()) {
                TextureAtlasManager.registerTint(ctx, spriteKey, 0xFFFFFF);
            }
            TextureAtlasManager.generateAllAtlases(ctx, request.outputDir());
            ColorMapManager.generateColorMaps(ctx, request.outputDir());
            ExportLogger.log("[GltfBuilder] Texture atlas generation complete");
            TimeLogger.logDuration("gltf_atlas_generation", TimeLogger.elapsedSince(tAtlas));

            // 3. UV重映射
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "重映射UV");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 3/4: Remapping UVs...");
            long tUvRemap = TimeLogger.now();

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
            TimeLogger.logDuration("gltf_uv_remap", TimeLogger.elapsedSince(tUvRemap));

            // 4. 组装glTF
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "组装glTF");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            ExportLogger.log("[GltfBuilder] Stage 4/4: Assembling glTF...");
            long tAssemble = TimeLogger.now();

            Path result = assembleGltf(request, geometryBin, finaluvBin);
            ExportLogger.log("[GltfBuilder] glTF assembly complete: " + result);
            TimeLogger.logDuration("gltf_assembly", TimeLogger.elapsedSince(tAssemble));

            return result;
        } catch (Exception e) {
            ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Export failed in write() method: " + e.getClass().getName() + ": " + e.getMessage());
            ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            if (e.getCause() != null) {
                ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
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
                ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Writer thread failed: " + e.getMessage());
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
        // Make texture/image lists thread-safe; material assembly runs in parallel
        List<Texture> textures = Collections.synchronizedList(new ArrayList<>());
            List<Image> images = Collections.synchronizedList(new ArrayList<>());
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
            long tMaterialAssembly = TimeLogger.now();

        try (MultiBinaryChunk chunk = new MultiBinaryChunk(binPath, gltf);
             MultiBinaryChunk uvChunk = new MultiBinaryChunk(uvBinPath, gltf);
             FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
             FileChannel uvChannel = FileChannel.open(finaluvBin, StandardOpenOption.READ)) {

                ExportLogger.log("[GltfBuilder] Opened binary files for reading");
                ExportLogger.log("[GltfBuilder] geometry.bin size: " + geometryChannel.size() + " bytes");
                ExportLogger.log("[GltfBuilder] finaluv.bin size: " + uvChannel.size() + " bytes");

                // Process materials sequentially (parallel processing causes buffer corruption)
                List<String> materialKeys = geometryIndex.getAllMaterialKeys();
                int totalMaterials = materialKeys.size();
                int processedMaterials = 0;

                ExportLogger.log("[GltfBuilder] Processing " + totalMaterials + " materials...");

                for (String matKey : materialKeys) {
                    try {
                        GeometryIndex.MaterialChunk matChunk = geometryIndex.getMaterial(matKey);

                        if (matChunk != null && processedMaterials % 100 == 0) {
                            ExportLogger.log(String.format("[GltfBuilder] Processing material: %s (quads: %d, hash: %d)",
                                matKey, matChunk.quadCount(), matKey.hashCode()));
                        }

                        // 从geometry.bin和finaluv.bin读取该material的数据
                        assembleMaterialPrimitive(
                            matKey, matChunk,
                            geometryChannel, uvChannel,
                            gltf, chunk, uvChunk,
                            materials, meshes, nodes, textures, images, colorMapIndices
                        );

                        processedMaterials++;

                        // 进度提示（每10%）
                        if (totalMaterials > 10 && processedMaterials % Math.max(1, totalMaterials / 10) == 0) {
                            double progress = processedMaterials * 100.0 / totalMaterials;
                            ExportLogger.log(String.format("[GltfAssembly] %.0f%% (%d/%d materials)",
                                progress, processedMaterials, totalMaterials));
                        }
                    } catch (Exception e) {
                        ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Failed to assemble material: " + matKey);
                        ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Error details: " + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                        throw new IOException("Failed to assemble material: " + matKey, e);
                    }
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

                ExportLogger.log("[GltfBuilder] Binary chunks closed");
                ExportLogger.log(String.format("[GltfBuilder] Main binary files: %s", chunk.getAllPaths()));
                ExportLogger.log(String.format("[GltfBuilder] UV binary files: %s", uvChunk.getAllPaths()));
                TimeLogger.logDuration("gltf_material_assembly", TimeLogger.elapsedSince(tMaterialAssembly));

                // 验证文件大小与buffer声明匹配
                List<de.javagl.jgltf.impl.v2.Buffer> gltfBuffers = gltf.getBuffers();
                if (gltfBuffers != null) {
                    for (int i = 0; i < gltfBuffers.size(); i++) {
                        de.javagl.jgltf.impl.v2.Buffer buf = gltfBuffers.get(i);
                        String uri = buf.getUri();
                        int declaredSize = buf.getByteLength();
                        Path bufPath = request.outputDir().resolve(uri);
                        if (java.nio.file.Files.exists(bufPath)) {
                            long actualSize = java.nio.file.Files.size(bufPath);
                            ExportLogger.log(String.format("[GltfBuilder] Buffer[%d] %s: declared=%d, actual=%d %s",
                                i, uri, declaredSize, actualSize,
                                (declaredSize == actualSize) ? "✓" : "MISMATCH!"));
                            if (declaredSize != actualSize) {
                                ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Buffer size mismatch detected!");
                            }
                        } else {
                            ExportLogger.log(String.format("[GltfBuilder][ERROR] Buffer file not found: %s", bufPath));
                        }
                    }
                }

                ExportLogger.log("[GltfBuilder] Writing glTF file...");
                GltfAsset assetModel = new GltfAssetV2(gltf, null);
                GltfAssetWriter writer = new GltfAssetWriter();
                Path gltfPath = request.outputDir().resolve(request.baseName() + ".gltf");
                long tWriteGltf = TimeLogger.now();
                writer.writeJson(assetModel, gltfPath.toFile());
                ExportLogger.log("[GltfBuilder] glTF file written successfully: " + gltfPath);
                TimeLogger.logDuration("gltf_write_json", TimeLogger.elapsedSince(tWriteGltf));

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
            ExportLogger.logGltfDebug("[GltfBuilder][ERROR] glTF assembly failed: " + e.getClass().getName() + ": " + e.getMessage());
            ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                ExportLogger.log("    at " + element.toString());
            }
            if (e.getCause() != null) {
                ExportLogger.logGltfDebug("[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    ExportLogger.log("    at " + element.toString());
                }
            }
            e.printStackTrace();
            throw new IOException("glTF assembly failed", e);
        }
    }

    /**
     * 组装单个material的primitive（直接组装，数据已在采样时去重）
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

        // 数据已在采样时去重，直接构建顶点和索引数组
        int quadCount = matChunk.quadCount();
        int vertexCount = quadCount * 4;  // 每个quad 4个顶点
        int indexCount = quadCount * 6;   // 每个quad 6个索引 (2个三角形)

        List<Float> positions = new ArrayList<>(vertexCount * 3);
        List<Float> uv0 = new ArrayList<>(vertexCount * 2);
        List<Float> uv1 = new ArrayList<>(vertexCount * 2);
        List<Float> colors = new ArrayList<>(vertexCount * 4);
        List<Integer> indices = new ArrayList<>(indexCount);
        boolean doubleSided = false;

        // OPTIMIZATION: Larger buffers for batch reading (1.3-1.5x faster I/O)
        // Read up to 512 quads at once to reduce system calls
        final int BATCH_SIZE = 512;
        final int GEOMETRY_BATCH_BYTES = BATCH_SIZE * BYTES_PER_QUAD_GEOMETRY;
        final int UV_BATCH_BYTES = BATCH_SIZE * BYTES_PER_QUAD_UV;

        ByteBuffer geometryBatchBuffer = ByteBuffer.allocateDirect(GEOMETRY_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvBatchBuffer = ByteBuffer.allocateDirect(UV_BATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int materialHashValue = matKey.hashCode();
        int skippedMismatches = 0;

        // OPTIMIZATION: Sort quadOffsets for sequential disk reads (2-3x faster I/O)
        List<Long> sortedOffsets = new ArrayList<>(matChunk.quadOffsets());
        Collections.sort(sortedOffsets);

        ExportLogger.log(String.format("[GltfBuilder] Reading material %s (hash: %d) with %d offsets",
            matKey, materialHashValue, sortedOffsets.size()));
        if (sortedOffsets.size() > 0) {
            ExportLogger.log(String.format("[GltfBuilder] First 5 offsets: %s",
                sortedOffsets.subList(0, Math.min(5, sortedOffsets.size()))));
        }

        // 批量读取quad数据并直接构建顶点数组（数据已在采样时去重）
        int totalQuads = sortedOffsets.size();
        int offsetIndex = 0;
        int currentVertexBase = 0;  // 当前quad的起始顶点索引

        while (offsetIndex < totalQuads) {
            // Group contiguous quad offsets for batch reading
            long startOffset = sortedOffsets.get(offsetIndex);
            int rangeCount = 1;
            while (rangeCount < BATCH_SIZE && offsetIndex + rangeCount < totalQuads) {
                long expectedNext = startOffset + rangeCount;
                long nextOffset = sortedOffsets.get(offsetIndex + rangeCount);
                if (nextOffset != expectedNext) break;
                rangeCount++;
            }

            long geometryBatchPos = startOffset * BYTES_PER_QUAD_GEOMETRY;
            long uvBatchPos = startOffset * BYTES_PER_QUAD_UV;

            // Read batch into buffers
            geometryBatchBuffer.clear();
            geometryBatchBuffer.limit(rangeCount * BYTES_PER_QUAD_GEOMETRY);
            geometryChannel.position(geometryBatchPos);
            readFully(geometryChannel, geometryBatchBuffer);
            geometryBatchBuffer.flip();

            uvBatchBuffer.clear();
            uvBatchBuffer.limit(rangeCount * BYTES_PER_QUAD_UV);
            uvChannel.position(uvBatchPos);
            readFully(uvChannel, uvBatchBuffer);
            uvBatchBuffer.flip();

            // Process all quads in this batch
            for (int i = 0; i < rangeCount; i++) {
                int geoBase = i * BYTES_PER_QUAD_GEOMETRY;
                geometryBatchBuffer.position(geoBase);

                // geometry.bin格式: materialHash(4) + spriteId(4) + overlayId(4) + doubleSided(1) + pad(3) + pos(48) + normal(12) + color(64)
                int materialHash = geometryBatchBuffer.getInt();

                // Debug first few mismatches
                if (materialHash != materialHashValue && skippedMismatches < 3) {
                    ExportLogger.log(String.format("[GltfBuilder][DEBUG] Hash mismatch at offset %d: expected %d, got %d",
                        sortedOffsets.get(offsetIndex + i), materialHashValue, materialHash));
                }

                geometryBatchBuffer.getInt(); // skip spriteId (不再需要)
                geometryBatchBuffer.getInt(); // skip overlayId
                byte doubleSidedByte = geometryBatchBuffer.get();
                geometryBatchBuffer.get(); // skip padding
                geometryBatchBuffer.get();
                geometryBatchBuffer.get();

                // Skip quads that belong to other materials
                if (materialHash != materialHashValue) {
                    skippedMismatches++;
                    continue;  // 注意：不读取数据，不递增currentVertexBase
                }

                // 读取positions (12 floats = 4 vertices × 3 coords)
                for (int j = 0; j < 12; j++) {
                    float pos = geometryBatchBuffer.getFloat();
                    if (Float.isNaN(pos) || Float.isInfinite(pos)) {
                        ExportLogger.log(String.format("[GltfBuilder][ERROR] Invalid position value (NaN/Inf) in material %s at offset %d, vertex component %d",
                            matKey, sortedOffsets.get(offsetIndex + i), j));
                        pos = 0f; // Replace with 0 to avoid corruption
                    }
                    positions.add(pos);
                }

                // 跳过normal (3 floats)
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();

                // 读取colors (16 floats = 4 vertices × 4 RGBA)
                for (int j = 0; j < 16; j++) {
                    colors.add(geometryBatchBuffer.getFloat());
                }

                // 读取UV
                uvBatchBuffer.position(i * BYTES_PER_QUAD_UV);
                for (int j = 0; j < 8; j++) {
                    uv0.add(uvBatchBuffer.getFloat());
                }
                for (int j = 0; j < 8; j++) {
                    uv1.add(uvBatchBuffer.getFloat());
                }

                // 生成索引 (quad -> 2 triangles)
                // Triangle 1: v0, v1, v2
                indices.add(currentVertexBase + 0);
                indices.add(currentVertexBase + 1);
                indices.add(currentVertexBase + 2);
                // Triangle 2: v0, v2, v3
                indices.add(currentVertexBase + 0);
                indices.add(currentVertexBase + 2);
                indices.add(currentVertexBase + 3);

                currentVertexBase += 4;  // 只有成功添加顶点后才递增

                if (doubleSidedByte != 0) {
                    doubleSided = true;
                }
            }

            offsetIndex += rangeCount;
        }

        if (skippedMismatches > 0) {
            ExportLogger.log(String.format("[GltfBuilder][WARN] Skipped %d quads for material %s due to hash mismatch", skippedMismatches, matKey));
        }

        // 验证数据完整性
        if (positions.isEmpty() || indices.isEmpty()) {
            ExportLogger.log("[GltfBuilder] Skipping material " + matKey + " (no valid geometry)");
            return;
        }

        int finalVertexCount = positions.size() / 3;
        int finalIndexCount = indices.size();

        // 日志:记录数据大小
        ExportLogger.log(String.format("[GltfBuilder] Material %s: read %d quads from %d offsets, got vertices=%d, indices=%d",
            matKey, (finalVertexCount / 4), totalQuads, finalVertexCount, finalIndexCount));
        ExportLogger.log(String.format("[GltfBuilder] Material %s hash: %d, skipped mismatches: %d",
            matKey, materialHashValue, skippedMismatches));

        // DEBUG: 验证数据一致性
        int expectedPosSize = finalVertexCount * 3;
        int expectedUv0Size = finalVertexCount * 2;
        int expectedColorSize = finalVertexCount * 4;
        if (positions.size() != expectedPosSize) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] Position size mismatch: expected %d, got %d",
                expectedPosSize, positions.size()));
        }
        if (uv0.size() != expectedUv0Size) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] UV0 size mismatch: expected %d, got %d",
                expectedUv0Size, uv0.size()));
        }
        if (colors.size() != expectedColorSize) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] Color size mismatch: expected %d, got %d",
                expectedColorSize, colors.size()));
        }

        // 转换为数组
        float[] posArray = new float[positions.size()];
        float[] uv0Array = new float[uv0.size()];
        float[] uv1Array = new float[uv1.size()];
        float[] colorArray = new float[colors.size()];
        int[] indexArray = new int[indices.size()];

        for (int i = 0; i < positions.size(); i++) posArray[i] = positions.get(i);
        for (int i = 0; i < uv0.size(); i++) uv0Array[i] = uv0.get(i);
        for (int i = 0; i < uv1.size(); i++) uv1Array[i] = uv1.get(i);
        for (int i = 0; i < colors.size(); i++) colorArray[i] = colors.get(i);
        for (int i = 0; i < indices.size(); i++) indexArray[i] = indices.get(i);

        // 计算边界框
        float[] posMin = computeMin(posArray, 3);
        float[] posMax = computeMax(posArray, 3);

        // Validate bounds for NaN
        boolean hasNaN = false;
        for (int i = 0; i < posMin.length; i++) {
            if (Float.isNaN(posMin[i]) || Float.isNaN(posMax[i])) {
                hasNaN = true;
                ExportLogger.log(String.format("[GltfBuilder][ERROR] NaN detected in bounds for material %s: min[%d]=%f, max[%d]=%f",
                    matKey, i, posMin[i], i, posMax[i]));
            }
        }
        if (hasNaN) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] Material %s has NaN in position bounds. First 10 positions: %s",
                matKey, java.util.Arrays.toString(java.util.Arrays.copyOf(posArray, Math.min(10, posArray.length)))));
            // Skip this material to avoid corrupting the glTF
            return;
        }

        // 写入glTF buffers
        MultiBinaryChunk.Slice posSlice = chunk.writeFloatArray(posArray, posArray.length);
        int posView = addView(gltf, posSlice.bufferIndex(), posSlice.byteOffset(), posArray.length * 4, 34962);
        int posAcc = addAccessor(gltf, posView, finalVertexCount, "VEC3", 5126, posMin, posMax);

        // Check for potential integer overflow
        if (posSlice.byteOffset() < 0) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] Integer overflow detected for material %s: position byteOffset=%d",
                matKey, posSlice.byteOffset()));
        }

        MultiBinaryChunk.Slice uv0Slice = uvChunk.writeFloatArray(uv0Array, uv0Array.length);
        int uv0View = addView(gltf, uv0Slice.bufferIndex(), uv0Slice.byteOffset(), uv0Array.length * 4, 34962);
        int uv0Acc = addAccessor(gltf, uv0View, finalVertexCount, "VEC2", 5126, null, null);

        if (uv0Slice.byteOffset() < 0) {
            ExportLogger.log(String.format("[GltfBuilder][ERROR] Integer overflow detected for material %s: uv0 byteOffset=%d",
                matKey, uv0Slice.byteOffset()));
        }

        int uv1Acc = -1;
        boolean hasUV1 = false;
        for (float f : uv1Array) {
            if (f != 0) {
                hasUV1 = true;
                break;
            }
        }
        if (hasUV1) {
            MultiBinaryChunk.Slice uv1Slice = uvChunk.writeFloatArray(uv1Array, uv1Array.length);
            int uv1View = addView(gltf, uv1Slice.bufferIndex(), uv1Slice.byteOffset(), uv1Array.length * 4, 34962);
            uv1Acc = addAccessor(gltf, uv1View, finalVertexCount, "VEC2", 5126, null, null);
        }

        MultiBinaryChunk.Slice colorSlice = chunk.writeFloatArray(colorArray, colorArray.length);
        int colorView = addView(gltf, colorSlice.bufferIndex(), colorSlice.byteOffset(), colorArray.length * 4, 34962);
        int colorAcc = addAccessor(gltf, colorView, finalVertexCount, "VEC4", 5126, null, null);

        MultiBinaryChunk.Slice idxSlice = chunk.writeIntArray(indexArray, indexArray.length);
        int idxView = addView(gltf, idxSlice.bufferIndex(), idxSlice.byteOffset(), indexArray.length * 4, 34963);
        int idxAcc = addAccessor(gltf, idxView, finalIndexCount, "SCALAR", 5125, null, null);

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
        material.setDoubleSided(doubleSided);

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
        if (hasUV1) {
            attrs.put("TEXCOORD_1", uv1Acc);
        }
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

        // Validate bufferView doesn't exceed buffer bounds
        List<de.javagl.jgltf.impl.v2.Buffer> buffers = gltf.getBuffers();
        if (buffers != null && bufferIndex < buffers.size()) {
            Integer bufferSize = buffers.get(bufferIndex).getByteLength();
            // buffer.byteLength is only populated when the chunk is closed; skip validation while null
            if (bufferSize != null) {
                long viewEnd = (long) byteOffset + (long) byteLength;
                if (viewEnd > bufferSize) {
                    ExportLogger.log(String.format("[GltfBuilder][ERROR] BufferView exceeds buffer bounds: buffer[%d] size=%d, view offset=%d, length=%d, end=%d",
                        bufferIndex, bufferSize, byteOffset, byteLength, viewEnd));
                }
            }
        }

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
