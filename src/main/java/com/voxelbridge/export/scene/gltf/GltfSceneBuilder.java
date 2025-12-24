package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.TextureExportPipeline;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.client.ProgressNotifier;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

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
    private static final QuadBatch POISON_PILL = new QuadBatch(null, null, null, null, null, null, null, null, false, null);
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
        boolean doubleSided,
        String bucketKey
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

        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Initialized streaming geometry pipeline");
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
        String animName = resolveAnimationName(spriteKey);
        String bucketKey = animName != null ? animName : materialGroupKey;

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
                positions, uv0, uv1, normal, colors, doubleSided, bucketKey
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        Minecraft mc = ctx.getMc();

        try {
            // 1. 
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.SAMPLING, "Sampling complete");
            ExportProgressTracker.setPhasePercent(null);
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 1/4: Finalizing sampling...");
            long tFinalizeSampling = VoxelBridgeLogger.now();

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
            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Sampling complete. Total quads: %d", totalQuads));
            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Materials: %d", geometryIndex.size()));
            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Sprites: %d", spriteIndex.size()));
            VoxelBridgeLogger.duration("gltf_finalize_sampling", VoxelBridgeLogger.elapsedSince(tFinalizeSampling));

            if (totalQuads == 0) {
                throw new IOException("No geometry data was written during sampling phase");
            }

            // 2. 
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.ATLAS, "Building atlases");
            ExportProgressTracker.setPhasePercent(null);
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 2/4: Generating texture atlases...");
            long tAtlas = VoxelBridgeLogger.now();

            TextureExportPipeline.build(ctx, request.outputDir(), spriteIndex.getAllKeys());
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Texture atlas generation complete");
            VoxelBridgeLogger.duration("gltf_atlas_generation", VoxelBridgeLogger.elapsedSince(tAtlas));

            // 3. UV?
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "Remapping UVs");
            ExportProgressTracker.setPhasePercent(0.0f);
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 3/4: Remapping UVs...");
            long tUvRemap = VoxelBridgeLogger.now();

            Path geometryBin = request.outputDir().resolve("geometry.bin");
            Path uvrawBin = request.outputDir().resolve("uvraw.bin");
            Path finaluvBin = request.outputDir().resolve("finaluv.bin");

            if (!java.nio.file.Files.exists(geometryBin)) {
                throw new IOException("geometry.bin not found at: " + geometryBin);
            }
            if (!java.nio.file.Files.exists(uvrawBin)) {
                throw new IOException("uvraw.bin not found at: " + uvrawBin);
            }

            PhaseProgress phase = new PhaseProgress();
            UVRemapper.remapUVs(geometryBin, uvrawBin, finaluvBin, spriteIndex, ctx, frac -> {
                float mapped = (float) (0.6f * Math.max(0d, Math.min(1d, frac)));
                if (phase.shouldPush(mapped)) {
                    ExportProgressTracker.setPhasePercent(mapped);
                    ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
                }
            });
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] UV remapping complete");
            VoxelBridgeLogger.duration("gltf_uv_remap", VoxelBridgeLogger.elapsedSince(tUvRemap));
            ExportProgressTracker.setPhasePercent(0.6f);
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            // 4. glTF
            ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "Assembling glTF");
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 4/4: Assembling glTF...");
            long tAssemble = VoxelBridgeLogger.now();

            Path result = assembleGltf(request, geometryBin, finaluvBin, phase);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] glTF assembly complete: " + result);
            VoxelBridgeLogger.duration("gltf_assembly", VoxelBridgeLogger.elapsedSince(tAssemble));
            ExportProgressTracker.setPhasePercent(1.0f);
            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

            return result;
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Export failed in write() method: " + e.getClass().getName() + ": " + e.getMessage());
            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                VoxelBridgeLogger.info(LogModule.GLTF, "    at " + element.toString());
            }
            if (e.getCause() != null) {
                VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    VoxelBridgeLogger.info(LogModule.GLTF, "    at " + element.toString());
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

                    // 
                    streamingWriter.writeQuad(
                        batch.bucketKey,
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
                VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Writer thread failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "VoxelBridge-StreamingWriter");
        writerThread.start();
    }

    /**
     * eometry.bininaluv.binglTF
     */
    private Path assembleGltf(SceneWriteRequest request, Path geometryBin, Path finaluvBin, PhaseProgress phase) throws IOException {
        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Starting glTF assembly...");
        VoxelBridgeLogger.memory("before_gltf_assembly");

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

            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Registering colormap textures...");
            List<Integer> colorMapIndices = registerColorMapTextures(request.outputDir(), textures, images, 0);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Colormap textures registered: " + colorMapIndices.size());
            long tMaterialAssembly = VoxelBridgeLogger.now();

        try (MultiBinaryChunk chunk = new MultiBinaryChunk(binPath, gltf);
             MultiBinaryChunk uvChunk = new MultiBinaryChunk(uvBinPath, gltf);
             FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ);
             FileChannel uvChannel = FileChannel.open(finaluvBin, StandardOpenOption.READ)) {

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Opened binary files for reading");
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] geometry.bin size: " + geometryChannel.size() + " bytes");
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] finaluv.bin size: " + uvChannel.size() + " bytes");

                // Process materials sequentially (parallel processing causes buffer corruption)
                List<String> materialKeys = geometryIndex.getAllMaterialKeys();
                int totalMaterials = materialKeys.size();
                int processedMaterials = 0;

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Processing " + totalMaterials + " materials...");

                for (String matKey : materialKeys) {
                    try {
                        GeometryIndex.MaterialChunk matChunk = geometryIndex.getMaterial(matKey);

                        if (matChunk != null && processedMaterials % 100 == 0) {
                            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Processing material: %s (quads: %d, hash: %d)",
                                matKey, matChunk.quadCount(), matKey.hashCode()));
                        }

                        // eometry.bininaluv.binaterial?
                        assembleMaterialPrimitive(
                            matKey, matChunk,
                            geometryChannel, uvChannel,
                            gltf, chunk, uvChunk,
                            materials, meshes, nodes, textures, images, colorMapIndices
                        );

                        processedMaterials++;

                        if (totalMaterials > 0) {
                            float frac = processedMaterials / (float) totalMaterials;
                            float mapped = 0.6f + 0.4f * frac;
                            if (phase.shouldPush(mapped)) {
                                ExportProgressTracker.setPhasePercent(mapped);
                                ProgressNotifier.showDetailed(ctx.getMc(), ExportProgressTracker.progress());
                            }
                        }
                    } catch (Exception e) {
                        VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Failed to assemble material: " + matKey);
                        VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Error details: " + e.getClass().getName() + ": " + e.getMessage());
                        e.printStackTrace();
                        throw new IOException("Failed to assemble material: " + matKey, e);
                    }
                }

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] All materials processed successfully");

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

                // ?glTF JSON
                chunk.close();
                uvChunk.close();

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Binary chunks closed");
                VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Main binary files: %s", chunk.getAllPaths()));
                VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] UV binary files: %s", uvChunk.getAllPaths()));
                VoxelBridgeLogger.duration("gltf_material_assembly", VoxelBridgeLogger.elapsedSince(tMaterialAssembly));

                // uffer
                List<de.javagl.jgltf.impl.v2.Buffer> gltfBuffers = gltf.getBuffers();
                if (gltfBuffers != null) {
                    for (int i = 0; i < gltfBuffers.size(); i++) {
                        de.javagl.jgltf.impl.v2.Buffer buf = gltfBuffers.get(i);
                        String uri = buf.getUri();
                        int declaredSize = buf.getByteLength();
                        Path bufPath = request.outputDir().resolve(uri);
                        if (java.nio.file.Files.exists(bufPath)) {
                            long actualSize = java.nio.file.Files.size(bufPath);
                            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Buffer[%d] %s: declared=%d, actual=%d %s",
                                i, uri, declaredSize, actualSize,
                                (declaredSize == actualSize) ? "OK" : "MISMATCH!"));
                            if (declaredSize != actualSize) {
                                VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Buffer size mismatch detected!");
                            }
                        } else {
                            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Buffer file not found: %s", bufPath));
                        }
                    }
                }

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Writing glTF file...");
                GltfAsset assetModel = new GltfAssetV2(gltf, null);
                GltfAssetWriter writer = new GltfAssetWriter();
                Path gltfPath = request.outputDir().resolve(request.baseName() + ".gltf");
                long tWriteGltf = VoxelBridgeLogger.now();
                writer.writeJson(assetModel, gltfPath.toFile());
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] glTF file written successfully: " + gltfPath);
                VoxelBridgeLogger.duration("gltf_write_json", VoxelBridgeLogger.elapsedSince(tWriteGltf));

                // ?
                if (!java.nio.file.Files.exists(gltfPath)) {
                    throw new IOException("glTF file was not created: " + gltfPath);
                }
                long gltfSize = java.nio.file.Files.size(gltfPath);
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] glTF file size: " + gltfSize + " bytes");
            }

            // 
            try {
                Files.deleteIfExists(geometryBin);
                Files.deleteIfExists(request.outputDir().resolve("uvraw.bin"));
                Files.deleteIfExists(finaluvBin);
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Temporary files cleaned up");
            } catch (IOException e) {
                VoxelBridgeLogger.warn(LogModule.GLTF, "[GltfBuilder][WARN] Failed to delete temporary files: " + e.getMessage());
            }

            Path finalPath = request.outputDir().resolve(request.baseName() + ".gltf");
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Assembly complete: " + finalPath);
            return finalPath;
        } catch (Exception e) {
            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] glTF assembly failed: " + e.getClass().getName() + ": " + e.getMessage());
            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Stack trace:");
            for (StackTraceElement element : e.getStackTrace()) {
                VoxelBridgeLogger.info(LogModule.GLTF, "    at " + element.toString());
            }
            if (e.getCause() != null) {
                VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                for (StackTraceElement element : e.getCause().getStackTrace()) {
                    VoxelBridgeLogger.info(LogModule.GLTF, "    at " + element.toString());
                }
            }
            e.printStackTrace();
            throw new IOException("glTF assembly failed", e);
        }
    }

    /**
     * materialrimitive
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

        // ?
        int quadCount = matChunk.quadCount();
        int vertexCount = quadCount * 4;  // quad 4?
        int indexCount = quadCount * 6;   // quad 6?(2)

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

        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Reading material %s (hash: %d) with %d offsets",
            matKey, materialHashValue, sortedOffsets.size()));
        if (sortedOffsets.size() > 0) {
            VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] First 5 offsets: %s",
                sortedOffsets.subList(0, Math.min(5, sortedOffsets.size()))));
        }

        // quad
        int totalQuads = sortedOffsets.size();
        int offsetIndex = 0;
        int currentVertexBase = 0;  // quad?

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

                // geometry.bin: materialHash(4) + spriteId(4) + overlayId(4) + doubleSided(1) + pad(3) + pos(48) + normal(12) + color(64)
                int materialHash = geometryBatchBuffer.getInt();

                // Debug first few mismatches
                if (materialHash != materialHashValue && skippedMismatches < 3) {
                    VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder][DEBUG] Hash mismatch at offset %d: expected %d, got %d",
                        sortedOffsets.get(offsetIndex + i), materialHashValue, materialHash));
                }

                geometryBatchBuffer.getInt(); // skip spriteId (?
                geometryBatchBuffer.getInt(); // skip overlayId
                byte doubleSidedByte = geometryBatchBuffer.get();
                geometryBatchBuffer.get(); // skip padding
                geometryBatchBuffer.get();
                geometryBatchBuffer.get();

                // Skip quads that belong to other materials
                if (materialHash != materialHashValue) {
                    skippedMismatches++;
                    continue;  // currentVertexBase
                }

                // positions (12 floats = 4 vertices  3 coords)
                for (int j = 0; j < 12; j++) {
                    float pos = geometryBatchBuffer.getFloat();
                    if (Float.isNaN(pos) || Float.isInfinite(pos)) {
                        VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Invalid position value (NaN/Inf) in material %s at offset %d, vertex component %d",
                            matKey, sortedOffsets.get(offsetIndex + i), j));
                        pos = 0f; // Replace with 0 to avoid corruption
                    }
                    positions.add(pos);
                }

                // normal (3 floats)
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();
                geometryBatchBuffer.getFloat();

                // colors (16 floats = 4 vertices  4 RGBA)
                for (int j = 0; j < 16; j++) {
                    colors.add(geometryBatchBuffer.getFloat());
                }

                // UV
                uvBatchBuffer.position(i * BYTES_PER_QUAD_UV);
                for (int j = 0; j < 8; j++) {
                    uv0.add(uvBatchBuffer.getFloat());
                }
                for (int j = 0; j < 8; j++) {
                    uv1.add(uvBatchBuffer.getFloat());
                }

                //  (quad -> 2 triangles)
                // Triangle 1: v0, v1, v2
                indices.add(currentVertexBase + 0);
                indices.add(currentVertexBase + 1);
                indices.add(currentVertexBase + 2);
                // Triangle 2: v0, v2, v3
                indices.add(currentVertexBase + 0);
                indices.add(currentVertexBase + 2);
                indices.add(currentVertexBase + 3);

                currentVertexBase += 4;  // 

                if (doubleSidedByte != 0) {
                    doubleSided = true;
                }
            }

            offsetIndex += rangeCount;
        }

        if (skippedMismatches > 0) {
            VoxelBridgeLogger.warn(LogModule.GLTF, String.format("[GltfBuilder][WARN] Skipped %d quads for material %s due to hash mismatch", skippedMismatches, matKey));
        }

        // ?
        if (positions.isEmpty() || indices.isEmpty()) {
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Skipping material " + matKey + " (no valid geometry)");
            return;
        }

        int finalVertexCount = positions.size() / 3;
        int finalIndexCount = indices.size();

        // :
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Material %s: read %d quads from %d offsets, got vertices=%d, indices=%d",
            matKey, (finalVertexCount / 4), totalQuads, finalVertexCount, finalIndexCount));
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Material %s hash: %d, skipped mismatches: %d",
            matKey, materialHashValue, skippedMismatches));

        // DEBUG: ?
        int expectedPosSize = finalVertexCount * 3;
        int expectedUv0Size = finalVertexCount * 2;
        int expectedColorSize = finalVertexCount * 4;
        if (positions.size() != expectedPosSize) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Position size mismatch: expected %d, got %d",
                expectedPosSize, positions.size()));
        }
        if (uv0.size() != expectedUv0Size) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] UV0 size mismatch: expected %d, got %d",
                expectedUv0Size, uv0.size()));
        }
        if (colors.size() != expectedColorSize) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Color size mismatch: expected %d, got %d",
                expectedColorSize, colors.size()));
        }

        // ?
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

        // ?
        float[] posMin = computeMin(posArray, 3);
        float[] posMax = computeMax(posArray, 3);

        // Validate bounds for NaN
        boolean hasNaN = false;
        for (int i = 0; i < posMin.length; i++) {
            if (Float.isNaN(posMin[i]) || Float.isNaN(posMax[i])) {
                hasNaN = true;
                VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] NaN detected in bounds for material %s: min[%d]=%f, max[%d]=%f",
                    matKey, i, posMin[i], i, posMax[i]));
            }
        }
        if (hasNaN) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Material %s has NaN in position bounds. First 10 positions: %s",
                matKey, java.util.Arrays.toString(java.util.Arrays.copyOf(posArray, Math.min(10, posArray.length)))));
            // Skip this material to avoid corrupting the glTF
            return;
        }

        // glTF buffers
        MultiBinaryChunk.Slice posSlice = chunk.writeFloatArray(posArray, posArray.length);
        int posView = addView(gltf, posSlice.bufferIndex(), posSlice.byteOffset(), posArray.length * 4, 34962);
        int posAcc = addAccessor(gltf, posView, finalVertexCount, "VEC3", 5126, posMin, posMax);

        // Check for potential integer overflow
        if (posSlice.byteOffset() < 0) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Integer overflow detected for material %s: position byteOffset=%d",
                matKey, posSlice.byteOffset()));
        }

        MultiBinaryChunk.Slice uv0Slice = uvChunk.writeFloatArray(uv0Array, uv0Array.length);
        int uv0View = addView(gltf, uv0Slice.bufferIndex(), uv0Slice.byteOffset(), uv0Array.length * 4, 34962);
        int uv0Acc = addAccessor(gltf, uv0View, finalVertexCount, "VEC2", 5126, null, null);

        if (uv0Slice.byteOffset() < 0) {
            VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] Integer overflow detected for material %s: uv0 byteOffset=%d",
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

        // material
        String sampleSprite = pickPrimarySprite(matChunk.usedSprites());
        VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
            "[TextureRegistry][MaterialSprites] matKey=%s sprites=%s picked=%s",
            matKey, matChunk.usedSprites(), sampleSprite));
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

        // mesh
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

    /**
     * Pick a stable primary sprite for a material: prefer entity:* sprites, otherwise first sorted.
     */
    private String pickPrimarySprite(Set<String> usedSprites) {
        if (usedSprites == null || usedSprites.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>(usedSprites);
        Collections.sort(list);
        //  item_frame/glow_item_frame ?sprite
        for (String s : list) {
            if (s.contains("item_frame")) {
                return s;
            }
        }
        for (String s : list) {
            if (s.startsWith("entity:")) {
                return s;
            }
        }
        return list.get(0);
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
                    VoxelBridgeLogger.error(LogModule.GLTF, String.format("[GltfBuilder][ERROR] BufferView exceeds buffer bounds: buffer[%d] size=%d, view offset=%d, length=%d, end=%d",
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

    private String resolveAnimationName(String spriteKey) {
        if (!ExportRuntimeConfig.isAnimationEnabled()) {
            return null;
        }
        if (spriteKey == null) return null;
        var repo = ctx.getTextureRepository();
        if (!repo.hasAnimation(spriteKey)) {
            detectAnimation(spriteKey, repo);
        }
        if (!repo.hasAnimation(spriteKey)) return null;
        return animationBaseName(spriteKey);
    }

    private void detectAnimation(String spriteKey, com.voxelbridge.export.texture.TextureRepository repo) {
        try {
            TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            ResourceLocation spriteLoc = com.voxelbridge.util.ResourceLocationUtil.sanitize(spriteKey);
            TextureAtlasSprite sprite = atlas.getSprite(spriteLoc);
            if (sprite != null) {
                AnimatedTextureHelper.extractFromSprite(spriteKey, sprite, repo);
                if (repo.hasAnimation(spriteKey)) return;
            }
        } catch (Exception ignored) {
            // fallback to metadata
        }
        try {
            ResourceLocation texLoc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            AnimatedTextureHelper.detectFromMetadata(spriteKey, texLoc, repo);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, "[Animation][WARN] glTF detection failed for " + spriteKey + ": " + e.getMessage());
        }
    }

    private String animationBaseName(String spriteKey) {
        String base = safe(spriteKey);
        boolean overlay = spriteKey.endsWith("_overlay") || spriteKey.contains("/overlay") || spriteKey.contains(":overlay");
        if (overlay && !base.endsWith("_overlay")) {
            base = base + "_overlay";
        }
        if (!base.endsWith("_animated")) {
            base = base + "_animated";
        }
        return base;
    }

    private String safe(String spriteKey) {
        if (spriteKey == null) return "unknown";
        return spriteKey.replace(':', '_').replace('/', '_');
    }

    private static final class PhaseProgress {
        private static final long INTERVAL_NANOS = 200_000_000L; // 0.2s
        private long lastUpdate = 0L;
        private float lastPercent = -1f;

        boolean shouldPush(float percent) {
            long now = System.nanoTime();
            if (percent < 0f) percent = 0f;
            if (percent > 1f) percent = 1f;
            boolean enoughDelta = Math.abs(percent - lastPercent) >= 0.01f; // >=1%
            boolean enoughTime = now - lastUpdate >= INTERVAL_NANOS;
            if (enoughDelta || enoughTime) {
                lastPercent = percent;
                lastUpdate = now;
                return true;
            }
            return false;
        }
    }
}





