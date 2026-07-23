package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.ir.*;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.core.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.export.texture.UvMapper;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import de.javagl.jgltf.impl.v2.*;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming geometry processing pipeline (refactored)
 * 1. Receive Quad -> Stream to geometry.bin + uvraw.bin
 * 2. Sampling complete -> Generate atlas
 * 3. UV remapping -> uvraw.bin -> finaluv.bin
 * 4. Assemble glTF -> Build directly from geometry.bin + finaluv.bin
 */
public final class GltfSceneBuilder implements IrSink, IrBulkQuadSink {
    private final ExportState state;
    private final Path outputDir;
    private final TextureRegistry textureRegistry;
    private final ProgressReporter progressReporter;
    private final ExportOptions options;
    private String sceneLightmapRelativePath;

    // Streaming writer
    private final StreamingGeometryWriter streamingWriter;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;
    private final Map<String, String> bucketVisualMaterialKeys = new ConcurrentHashMap<>();
    private final Map<String, QuadSemantic> bucketSemantics = new ConcurrentHashMap<>();
    private final Map<QuadSemantic, Integer> internalSemanticIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextInternalSemanticId = new AtomicInteger();

    // Thread communication
    private static final QuadBatch POISON_PILL =
        new QuadBatch(null, null, null, null, 0, null, null, null, null, null, null, null, null);
    // OPTIMIZATION: Increased queue capacity 4x to reduce sampling thread blocking
    // Large scenes with many quads benefit from larger producer-consumer buffer
    // Changed to Object to support both single QuadBatch and BulkQuadBatch
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(4096); // Capacity can be lower since items are now batches
    private final AtomicBoolean writerStarted = new AtomicBoolean(false);
    private final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    private Thread writerThread;

    // Temporary quad data structure (for queue)
    private record QuadBatch(
        String materialGroupKey,
        String spriteKey,
        String overlaySpriteKey,
        QuadSemantic semantic,
        int quadFlags,
        float[] positions,
        float[] uv0,
        float[] uv1,
        float[] lightUv,
        float[] midBlock,
        float[] normal,
        float[] colors,
        String bucketKey
    ) {}

    // OPTIMIZATION: Bulk batch for efficient transfer from ChunkDeduplicator
    private record BulkQuadBatch(
        String bucketKey,
        String materialGroupKey,
        // Arrays of arrays/data
        List<String> spriteKeys,
        List<String> overlaySpriteKeys,
        List<QuadSemantic> semantics,
        float[] flatPositions,
        float[] flatUv0s,
        float[] flatUv1s,
        float[] flatLightUvs,
        float[] flatMidBlocks,
        float[] flatNormals,
        float[] flatColors,
        int[] quadFlags
    ) {}

    public GltfSceneBuilder(ExportState state, Path outDir) throws IOException {
        this(state, outDir, ProgressReporter.NOOP, ExportOptions.fromRuntimeConfig());
    }

    public GltfSceneBuilder(ExportState state, Path outDir, ProgressReporter progressReporter) throws IOException {
        this(state, outDir, progressReporter, ExportOptions.fromRuntimeConfig());
    }

    public GltfSceneBuilder(ExportState state,
                            Path outDir,
                            ProgressReporter progressReporter,
                            ExportOptions options) throws IOException {
        this.state = state;
        this.outputDir = outDir;
        this.textureRegistry = new TextureRegistry(state);
        this.progressReporter = progressReporter != null ? progressReporter : ProgressReporter.NOOP;
        this.options = options != null ? options : ExportOptions.fromRuntimeConfig();

        // Create streaming indices
        this.spriteIndex = new SpriteIndex();
        this.geometryIndex = new GeometryIndex();

        // Create streaming writer (Single Temp File)
        Path geometryBin = outDir.resolve("geometry.bin");
        // Pass null for unused UV path (signature kept for compatibility if needed, but we ignore it)
        this.streamingWriter = new StreamingGeometryWriter(geometryBin, null, spriteIndex, geometryIndex);

        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Initialized streaming geometry pipeline (Paged)");
    }

    public void setSceneLightmapRelativePath(String relativePath) {
        this.sceneLightmapRelativePath = relativePath;
    }

    /**
     * Optimized batch addition.
     * Called by ChunkDeduplicator to reduce queue lock contention.
     */
    @Override
    public void addBatch(String materialGroupKey,
                         List<String> spriteKeys,
                         List<String> overlaySpriteKeys,
                         List<QuadSemantic> semantics,
                         float[] flatPositions,
                         float[] flatUv0s,
                         float[] flatUv1s,
                         float[] flatLightUvs,
                         float[] flatMidBlocks,
                         float[] flatNormals,
                         float[] flatColors,
                         int[] quadFlags) {
        
        if (materialGroupKey == null || spriteKeys.isEmpty()) return;
        
        startWriterThread();

        enqueue(new BulkQuadBatch(
            null, // Bucket key resolved per quad in writer
            materialGroupKey,
            spriteKeys, overlaySpriteKeys, semantics,
            flatPositions, flatUv0s, flatUv1s, flatLightUvs, flatMidBlocks, flatNormals, flatColors,
            quadFlags
        ));
    }

    @Override
    public void addQuad(String materialKey,
                        String spriteKey,
                        String overlaySpriteKey,
                        QuadSemantic semantic,
                        RenderLayer renderLayer,
                        TintMode tintMode,
                        boolean doubleSided,
                        boolean emissive,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] lightUv,
                        float[] midBlock,
                        float[] normal,
                        float[] colors) {
        if (materialKey == null || spriteKey == null) return;
        int quadFlags = IrFlags.encode(renderLayer, tintMode, doubleSided, emissive);
        semantic = exportSemantic(semantic);
        String bucketKey = resolveSemanticBucketKey(materialKey, spriteKey, semantic);

        // Colormap mode: all quads must have TEXCOORD_1; non-tinted points to reserved white slot
        if (options.colorMode() != null && options.colorMode().usesColormap()) {
            if (uv1 == null || uv1.length < 8) {
                float[] lut = ColorMapManager.remapColorUV(state, 0xFFFFFFFF);
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

        enqueue(new QuadBatch(
            materialKey, spriteKey, overlaySpriteKey, semantic, quadFlags,
            positions, uv0, uv1, lightUv, midBlock, normal, colors, bucketKey
        ));
    }

    public Path write(SceneWriteRequest request) throws IOException {
        try {
            // 1. 
            progressReporter.setStage(Stage.SAMPLING, "Sampling complete");
            progressReporter.setPhasePercent(null);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 1/3: Finalizing sampling...");
            long tFinalizeSampling = VoxelBridgeLogger.now();

            try {
                enqueue(POISON_PILL);
                if (writerThread != null) {
                    writerThread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Export interrupted during writer thread join", e);
            }
            throwIfWriterFailed();

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
            progressReporter.setStage(Stage.ATLAS, "Building atlases");
            progressReporter.setPhasePercent(null);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 2/3: Using prebuilt texture atlases...");

            // 3. glTF Assembly (Includes on-the-fly UV Remap)
            progressReporter.setStage(Stage.FINALIZE, "Assembling glTF");
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 3/3: Assembling glTF...");
            long tAssemble = VoxelBridgeLogger.now();

            Path geometryBin = request.outputDir().resolve("geometry.bin");
            if (!java.nio.file.Files.exists(geometryBin)) {
                throw new IOException("geometry.bin not found at: " + geometryBin);
            }

            PhaseProgress phase = new PhaseProgress();
            Path result = assembleGltf(request, geometryBin, phase);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] glTF assembly complete: " + result);
            VoxelBridgeLogger.duration("gltf_assembly", VoxelBridgeLogger.elapsedSince(tAssemble));
            progressReporter.setPhasePercent(1.0f);

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
                    Object item = queue.take();
                    if (item == POISON_PILL) break;

                    if (item instanceof QuadBatch batch) {
                        // 
                        streamingWriter.writeQuad(
                            batch.bucketKey,
                            batch.spriteKey,
                            batch.overlaySpriteKey,
                            batch.quadFlags,
                            batch.positions,
                            batch.uv0,
                            batch.uv1,
                            batch.lightUv,
                            batch.midBlock,
                            batch.normal,
                            batch.colors
                        );
                    } else if (item instanceof BulkQuadBatch bulk) {
                        // Iterate and write bulk items
                        int count = bulk.spriteKeys().size();
                        
                        // Pre-calculate default UV1 for ColorMap mode if needed
                        float[] defaultUv1 = null;
                        if (options.colorMode() != null && options.colorMode().usesColormap()) {
                            if (bulk.flatUv1s() == null || bulk.flatUv1s().length == 0) {
                                float[] lut = ColorMapManager.remapColorUV(state, 0xFFFFFFFF);
                                float u0 = lut[0], v0 = lut[1], u1v = lut[2], v1v = lut[3];
                                defaultUv1 = new float[]{
                                    u0, v0,
                                    u1v, v0,
                                    u1v, v1v,
                                    u0, v1v
                                };
                            }
                        }

                        int[] flags = bulk.quadFlags();
                        for (int i = 0; i < count; i++) {
                            String spriteKey = bulk.spriteKeys().get(i);
                            QuadSemantic semantic = bulk.semantics() != null && i < bulk.semantics().size()
                                ? exportSemantic(bulk.semantics().get(i))
                                : QuadSemantic.NONE;
                            String bucketKey = resolveSemanticBucketKey(
                                bulk.materialGroupKey(), spriteKey, semantic
                            );
                            
                            // Determine UV1 source and offset
                            float[] currentUv1 = bulk.flatUv1s();
                            int currentUv1Offset = i * 8;
                            
                            if (defaultUv1 != null) {
                                currentUv1 = defaultUv1;
                                currentUv1Offset = 0;
                            } else if (currentUv1 == null) {
                                // Safe fallback if no UV1 provided and not in ColorMap mode (StreamingWriter handles null/bounds)
                                currentUv1Offset = 0;
                            }

                            int quadFlags = (flags != null && i < flags.length) ? flags[i] : 0;
                            streamingWriter.writeQuadFlat(
                                bucketKey,
                                spriteKey,
                                bulk.overlaySpriteKeys().get(i),
                                quadFlags,
                                bulk.flatPositions(), i * 12,
                                bulk.flatUv0s(), i * 8,
                                currentUv1, currentUv1Offset,
                                bulk.flatLightUvs(), i * 8,
                                bulk.flatMidBlocks(), i * 16,
                                bulk.flatNormals(), i * 3,
                                bulk.flatColors(), i * 16
                            );
                        }
                    }
                }
            } catch (Throwable e) {
                writerFailure.compareAndSet(null, e);
                VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Writer thread failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "VoxelBridge-StreamingWriter");
        writerThread.start();
    }

    private void enqueue(Object item) {
        while (true) {
            Throwable failure = writerFailure.get();
            if (failure != null) {
                throw new IllegalStateException("glTF writer thread failed", failure);
            }
            try {
                if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while enqueueing glTF geometry", e);
            }
        }
    }

    private void throwIfWriterFailed() throws IOException {
        Throwable failure = writerFailure.get();
        if (failure != null) {
            throw new IOException("glTF writer thread failed", failure);
        }
    }

    /**
     * Efficiently reads large files using memory mapping.
     * Splits file into 1GB segments to bypass integer indexing limits and manage memory better.
     */
    private static final class SegmentedMappedReader implements AutoCloseable {
        private static final long SEGMENT_SIZE = (long) 1024 * 1024 * 1024; // 1GB
        private final List<java.nio.MappedByteBuffer> segments = new ArrayList<>();
        private final long fileSize;

        public SegmentedMappedReader(FileChannel channel) throws IOException {
            this.fileSize = channel.size();
            long position = 0;
            while (position < fileSize) {
                long remaining = fileSize - position;
                long size = Math.min(SEGMENT_SIZE, remaining);
                segments.add(channel.map(FileChannel.MapMode.READ_ONLY, position, size));
                position += size;
            }
        }

        /**
         * Reads data from the mapped file into the destination buffer.
         * Handles cross-segment reads seamlessly.
         */
        public void read(long offset, ByteBuffer dst) {
            int remaining = dst.remaining();
            long currentOffset = offset;
            
            while (remaining > 0) {
                int segmentIndex = (int) (currentOffset / SEGMENT_SIZE);
                long offsetInSegment = currentOffset % SEGMENT_SIZE;
                
                if (segmentIndex >= segments.size()) {
                    throw new IndexOutOfBoundsException("Read beyond file size: " + currentOffset);
                }

                java.nio.MappedByteBuffer segment = segments.get(segmentIndex);
                // Duplicate to allow thread-safe access (though we use it single-threaded here)
                // and independent position tracking.
                ByteBuffer view = segment.duplicate();
                view.position((int) offsetInSegment);
                
                int availableInSegment = (int) (SEGMENT_SIZE - offsetInSegment);
                // Last segment might be smaller
                if (segmentIndex == segments.size() - 1) {
                    availableInSegment = (int) (fileSize % SEGMENT_SIZE);
                    if (availableInSegment == 0 && fileSize > 0) availableInSegment = (int) SEGMENT_SIZE; // Full last segment
                    availableInSegment -= offsetInSegment;
                }

                int toRead = Math.min(remaining, availableInSegment);
                
                // Limit view to what we want to read to avoid buffer overflows
                int originalLimit = view.limit();
                view.limit(view.position() + toRead);
                
                dst.put(view);
                
                currentOffset += toRead;
                remaining -= toRead;
            }
        }

        @Override
        public void close() {
            for (java.nio.MappedByteBuffer buffer : segments) {
                clean(buffer);
            }
            segments.clear();
        }

        /**
         * Reflective cleaner to work around mapped file locking on Windows.
         * Compatible with Java 8 through 21+.
         */
        private static void clean(java.nio.MappedByteBuffer buffer) {
            if (buffer == null) return;
            try {
                // Java 9+ approach (jdk.internal.ref.Cleaner)
                // Use reflection to avoid compile-time dependency issues
                Class<?> unsafeClass;
                try {
                    unsafeClass = Class.forName("sun.misc.Unsafe");
                } catch (Exception e) {
                    // Try jdk.internal.misc.Unsafe for newer JDKs if sun.misc is hidden
                    return; 
                }
                
                java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object unsafe = theUnsafe.get(null);
                
                java.lang.reflect.Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
                invokeCleaner.invoke(unsafe, buffer);
            } catch (Exception e) {
                // Fallback for Java 8 or if Unsafe is inaccessible
                try {
                    java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                    cleanerMethod.setAccessible(true);
                    Object cleaner = cleanerMethod.invoke(buffer);
                    if (cleaner != null) {
                        java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                        cleanMethod.setAccessible(true);
                        cleanMethod.invoke(cleaner);
                    }
                } catch (Exception ignored) {
                    // Best effort
                }
            }
        }
    }

    /**
     * Assembles the final glTF asset by reading binary data and creating accessors/views.
     */
    private Path assembleGltf(SceneWriteRequest request, Path geometryBin, PhaseProgress phase) throws IOException {
        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Starting glTF assembly...");
        VoxelBridgeLogger.memory("before_gltf_assembly");

        try {
            GlTF gltf = new GlTF();
            Asset asset = new Asset();
            asset.setVersion("2.0");
            asset.setGenerator("VoxelBridge");
            gltf.setAsset(asset);
            gltf.setExtensionsUsed(List.of(
                "VOXELBRIDGE_minecraft_scene",
                "VOXELBRIDGE_minecraft_material"
            ));
            Map<String, Object> sceneContract = new LinkedHashMap<>();
            sceneContract.put("version", 2);
            sceneContract.put("minecraftVersion", "1.21.1");
            sceneContract.put("colorUvTexCoord", 1);
            sceneContract.put("lightUvTexCoord", 2);
            sceneContract.put("lightUvEncoding", "normalized-minecraft-0-240");
            sceneContract.put("midTexCoordTexCoord", 3);
            sceneContract.put("midTexCoordSemantic", "mc_midTexCoord");
            sceneContract.put("midBlockAttribute", "_VOXELBRIDGE_MID_BLOCK");
            sceneContract.put("midBlockEncoding", "iris-offset-to-block-center-times-64-emission-w");
            sceneContract.put("materialIdentityTexCoord", 4);
            sceneContract.put("materialIdentityEncoding", "index-in-u-into-materialIdentities");
            Map<QuadSemantic, Integer> semanticIds = buildSemanticDictionary(sceneContract);
            gltf.setExtensions(Map.of("VOXELBRIDGE_minecraft_scene", sceneContract));

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
            Sampler lightmapSampler = new Sampler();
            lightmapSampler.setMagFilter(9729);
            lightmapSampler.setMinFilter(9729);
            lightmapSampler.setWrapS(33071);
            lightmapSampler.setWrapT(33071);
            samplers.add(lightmapSampler);
            gltf.setSamplers(samplers);

            if (sceneLightmapRelativePath != null) {
                Image lightmapImage = new Image();
                lightmapImage.setUri(sceneLightmapRelativePath);
                images.add(lightmapImage);
                Texture lightmapTexture = new Texture();
                lightmapTexture.setSource(images.size() - 1);
                lightmapTexture.setSampler(1);
                textures.add(lightmapTexture);
                sceneContract.put("lightmapTexture", textures.size() - 1);
                sceneContract.put("lightmapEncoding", "minecraft-light-texture-16x16");
                sceneContract.put("lightmapColorSpace", "linear");
            }

            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Registering colormap textures...");
            List<Integer> colorMapIndices = registerColorMapTextures(request.outputDir(), textures, images, 0);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Colormap textures registered: " + colorMapIndices.size());
            long tMaterialAssembly = VoxelBridgeLogger.now();

        try (MultiBinaryChunk chunk = new MultiBinaryChunk(binPath, gltf);
             MultiBinaryChunk uvChunk = new MultiBinaryChunk(uvBinPath, gltf);
             FileChannel geometryChannel = FileChannel.open(geometryBin, StandardOpenOption.READ)) {

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Opened binary files for reading");
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] geometry.bin size: " + geometryChannel.size() + " bytes");

                // Initialize Memory Mapped Reader
                try (SegmentedMappedReader mappedReader = new SegmentedMappedReader(geometryChannel)) {
                    
                    // Process materials sequentially
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

                            // Assemble primitives for this material
                            assembleMaterialPrimitive(
                                matKey, matChunk,
                                mappedReader, // Pass mapped reader instead of channel
                                gltf, chunk, uvChunk,
                                materials, meshes, nodes, textures, images, colorMapIndices,
                                semanticIds
                            );

                            processedMaterials++;

                            if (totalMaterials > 0) {
                                float frac = processedMaterials / (float) totalMaterials;
                                float mapped = 0.6f + 0.4f * frac;
                                if (phase.shouldPush(mapped)) {
                                    progressReporter.setPhasePercent(mapped);
                                }
                            }
                        } catch (Exception e) {
                            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Failed to assemble material: " + matKey);
                            VoxelBridgeLogger.error(LogModule.GLTF, "[GltfBuilder][ERROR] Error details: " + e.getClass().getName() + ": " + e.getMessage());
                            e.printStackTrace();
                            throw new IOException("Failed to assemble material: " + matKey, e);
                        }
                    }
                } // MappedReader closed here (unmapped)

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

                // Close binary chunks to flush headers
                chunk.close();
                uvChunk.close();

                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Binary chunks closed");
                VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Main binary files: %s", chunk.getAllPaths()));
                VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] UV binary files: %s", uvChunk.getAllPaths()));
                VoxelBridgeLogger.duration("gltf_material_assembly", VoxelBridgeLogger.elapsedSince(tMaterialAssembly));

                // Validate buffers
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

                // Verify output
                if (!java.nio.file.Files.exists(gltfPath)) {
                    throw new IOException("glTF file was not created: " + gltfPath);
                }
                long gltfSize = java.nio.file.Files.size(gltfPath);
                VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] glTF file size: " + gltfSize + " bytes");
            }

            // Cleanup temp files
            try {
                Files.deleteIfExists(geometryBin);
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
     * Reads a material chunk and assembles glTF primitives.
     */
    private void assembleMaterialPrimitive(
        String matKey,
        GeometryIndex.MaterialChunk matChunk,
        SegmentedMappedReader mappedReader,
        GlTF gltf,
        MultiBinaryChunk chunk,
        MultiBinaryChunk uvChunk,
        List<Material> materials,
        List<Mesh> meshes,
        List<Node> nodes,
        List<Texture> textures,
        List<Image> images,
        List<Integer> colorMapIndices,
        Map<QuadSemantic, Integer> semanticIds
    ) throws IOException {
        if (matChunk == null || matChunk.quadCount() == 0) return;

        // Calculate buffer sizes
        int totalQuadCount = matChunk.quadCount();
        int maxVertexCount = totalQuadCount * 4;  // 4 verts per quad
        int maxIndexCount = totalQuadCount * 6;   // 6 indices per quad

        // OPTIMIZATION: Use primitive arrays instead of Lists to avoid boxing overhead
        float[] posArray = new float[maxVertexCount * 3];
        float[] uv0Array = new float[maxVertexCount * 2];
        float[] uv1Array = new float[maxVertexCount * 2];
        float[] lightUvArray = new float[maxVertexCount * 2];
        float[] midTexCoordArray = new float[maxVertexCount * 2];
        float[] midBlockArray = new float[maxVertexCount * 4];
        float[] normalArray = new float[maxVertexCount * 3];
        float[] colorArray = new float[maxVertexCount * 4];
        int[] indexArray = new int[maxIndexCount];
        
        int posIdx = 0;
        int uv0Idx = 0;
        int uv1Idx = 0;
        int lightUvIdx = 0;
        int midTexCoordIdx = 0;
        int midBlockIdx = 0;
        int normalIdx = 0;
        int colIdx = 0;
        int idxIdx = 0;
        
        boolean doubleSided = false;
        boolean emissive = false;
        RenderLayer materialRenderLayer = RenderLayer.UNKNOWN;

        // 64KB Page Buffer (Interleaved Data)
        // Format: [Hash(4), Sprite(4), Overlay(4), Flags(4), Pos(48), Norm(12),
        // Color(64), UV0(32), UV1(32), LightUV(32), at_midBlock(64)] = 300 bytes
        ByteBuffer pageBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        
        int materialHashValue = matKey.hashCode();
        int skippedMismatches = 0;
        int currentVertexBase = 0;
        boolean atlasEnabled = com.voxelbridge.export.texture.UvRemapUtil.isAtlasEnabled(options);
        boolean isColormapMode = com.voxelbridge.export.texture.UvRemapUtil.isColormapMode(options);

        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Reading material %s (hash: %d) with %d pages",
            matKey, materialHashValue, matChunk.pages().size()));

        for (GeometryIndex.PageInfo page : matChunk.pages()) {
            long pageOffset = page.byteOffset();
            int quadsInPage = page.quadCount();
            
            // Seek and read page from MAPPED READER
            pageBuffer.clear();
            pageBuffer.limit(quadsInPage * StreamingGeometryWriter.BYTES_PER_QUAD);
            mappedReader.read(pageOffset, pageBuffer);
            pageBuffer.flip();
            
            for (int i = 0; i < quadsInPage; i++) {
                // Read Interleaved Data
                int materialHash = pageBuffer.getInt();
                int spriteId = pageBuffer.getInt();
                int overlaySpriteId = pageBuffer.getInt();
                int flags = pageBuffer.getInt();
                
                // Validate Hash
                if (materialHash != materialHashValue) {
                    pageBuffer.position(
                        pageBuffer.position() + StreamingGeometryWriter.BYTES_PER_QUAD - 16
                    );
                    skippedMismatches++;
                    continue;
                }
                
                // Read Geometry
                // Pos (12 floats)
                for (int j=0; j<12; j++) posArray[posIdx++] = pageBuffer.getFloat();
                
                float nx = pageBuffer.getFloat();
                float ny = pageBuffer.getFloat();
                float nz = pageBuffer.getFloat();
                for (int vertex = 0; vertex < 4; vertex++) {
                    normalArray[normalIdx++] = nx;
                    normalArray[normalIdx++] = ny;
                    normalArray[normalIdx++] = nz;
                }
                
                // Color (16 floats)
                for (int j=0; j<16; j++) colorArray[colIdx++] = pageBuffer.getFloat();
                
                // Read UVs (8 floats each)
                float[] qUv0 = new float[8];
                float[] qUv1 = new float[8];
                for (int j=0; j<8; j++) qUv0[j] = pageBuffer.getFloat();
                for (int j=0; j<8; j++) qUv1[j] = pageBuffer.getFloat();
                for (int j=0; j<8; j++) lightUvArray[lightUvIdx++] = pageBuffer.getFloat();
                for (int j=0; j<16; j++) midBlockArray[midBlockIdx++] = pageBuffer.getFloat();
                
                // --- ON-THE-FLY UV REMAP ---
                if (atlasEnabled) {
                    String spriteKey = spriteIndex.getKey(spriteId);
                    String overlayKey = overlaySpriteId >= 0 ? spriteIndex.getKey(overlaySpriteId) : null;
                    
                    // Remap UV0
                    if (com.voxelbridge.export.texture.UvRemapUtil.shouldRemap(state, spriteKey, options)) {
                        for (int v = 0; v < 4; v++) {
                            float[] remapped = UvMapper.remapUv(state, spriteKey, qUv0[v * 2], qUv0[v * 2 + 1], options);
                            qUv0[v * 2] = remapped[0];
                            qUv0[v * 2 + 1] = remapped[1];
                        }
                    }
                    
                    // Remap UV1 (Overlay)
                    if (!isColormapMode && com.voxelbridge.export.texture.UvRemapUtil.shouldRemap(state, overlayKey, options)) {
                         boolean hasUV1 = false;
                         for (float f : qUv1) if (f != 0) { hasUV1 = true; break; }
                         if (hasUV1) {
                             for (int v = 0; v < 4; v++) {
                                 float[] remapped = UvMapper.remapUv(state, overlayKey, qUv1[v * 2], qUv1[v * 2 + 1], options);
                                 qUv1[v * 2] = remapped[0];
                                 qUv1[v * 2 + 1] = remapped[1];
                             }
                         }
                    }
                }
                
                // Store UVs
                for (float f : qUv0) uv0Array[uv0Idx++] = f;
                for (float f : qUv1) uv1Array[uv1Idx++] = f;
                float midU = 0f;
                float midV = 0f;
                for (int vertex = 0; vertex < 4; vertex++) {
                    midU += qUv0[vertex * 2];
                    midV += qUv0[vertex * 2 + 1];
                }
                midU *= 0.25f;
                midV *= 0.25f;
                for (int vertex = 0; vertex < 4; vertex++) {
                    midTexCoordArray[midTexCoordIdx++] = midU;
                    midTexCoordArray[midTexCoordIdx++] = midV;
                }
                
                // Indices
                indexArray[idxIdx++] = currentVertexBase;
                indexArray[idxIdx++] = currentVertexBase + 1;
                indexArray[idxIdx++] = currentVertexBase + 2;
                indexArray[idxIdx++] = currentVertexBase;
                indexArray[idxIdx++] = currentVertexBase + 2;
                indexArray[idxIdx++] = currentVertexBase + 3;
                
                currentVertexBase += 4;
                
                if (IrFlags.isDoubleSided(flags)) doubleSided = true;
                if (IrFlags.isEmissive(flags)) emissive = true;
                RenderLayer quadLayer = IrFlags.decodeRenderLayer(flags);
                if (renderLayerPriority(quadLayer) > renderLayerPriority(materialRenderLayer)) {
                    materialRenderLayer = quadLayer;
                }
            }
        }

        if (skippedMismatches > 0) {
            VoxelBridgeLogger.warn(LogModule.GLTF, String.format("[GltfBuilder][WARN] Skipped %d quads for material %s due to hash mismatch", skippedMismatches, matKey));
        }

        // Validate data validity
        if (posIdx == 0 || idxIdx == 0) {
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Skipping material " + matKey + " (no valid geometry)");
            return;
        }

        // Handle skipped quads (resize arrays if necessary)
        if (posIdx < posArray.length) {
             posArray = Arrays.copyOf(posArray, posIdx);
             uv0Array = Arrays.copyOf(uv0Array, uv0Idx);
             uv1Array = Arrays.copyOf(uv1Array, uv1Idx);
             lightUvArray = Arrays.copyOf(lightUvArray, lightUvIdx);
             midTexCoordArray = Arrays.copyOf(midTexCoordArray, midTexCoordIdx);
             midBlockArray = Arrays.copyOf(midBlockArray, midBlockIdx);
             normalArray = Arrays.copyOf(normalArray, normalIdx);
             colorArray = Arrays.copyOf(colorArray, colIdx);
             indexArray = Arrays.copyOf(indexArray, idxIdx);
        }

        int finalVertexCount = posArray.length / 3;
        int finalIndexCount = indexArray.length;
        String visualMatKey = bucketVisualMaterialKeys.getOrDefault(matKey, matKey);
        QuadSemantic semantic = bucketSemantics.getOrDefault(matKey, QuadSemantic.NONE);
        Integer semanticId = semanticIds.get(semantic);

        // Log stats
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Material %s: read %d quads from %d pages, got vertices=%d, indices=%d",
            matKey, (finalVertexCount / 4), matChunk.pages().size(), finalVertexCount, finalIndexCount));
        VoxelBridgeLogger.info(LogModule.GLTF, String.format("[GltfBuilder] Material %s hash: %d, skipped mismatches: %d",
            matKey, materialHashValue, skippedMismatches));

        // Calculate bounds
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

        MultiBinaryChunk.Slice normalSlice = chunk.writeFloatArray(normalArray, normalArray.length);
        int normalView = addView(gltf, normalSlice.bufferIndex(), normalSlice.byteOffset(), normalArray.length * 4, 34962);
        int normalAcc = addAccessor(gltf, normalView, finalVertexCount, "VEC3", 5126, null, null);

        float[] tangentArray = computeTangents(
            posArray, uv0Array, normalArray, indexArray
        );
        MultiBinaryChunk.Slice tangentSlice = chunk.writeFloatArray(tangentArray, tangentArray.length);
        int tangentView = addView(gltf, tangentSlice.bufferIndex(), tangentSlice.byteOffset(), tangentArray.length * 4, 34962);
        int tangentAcc = addAccessor(gltf, tangentView, finalVertexCount, "VEC4", 5126, null, null);

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

        // Keep TEXCOORD_n contiguous. Several glTF consumers, including Blender,
        // stop discovering UV sets at the first missing index.
        MultiBinaryChunk.Slice uv1Slice = uvChunk.writeFloatArray(uv1Array, uv1Array.length);
        int uv1View = addView(gltf, uv1Slice.bufferIndex(), uv1Slice.byteOffset(), uv1Array.length * 4, 34962);
        int uv1Acc = addAccessor(gltf, uv1View, finalVertexCount, "VEC2", 5126, null, null);

        // glTF custom vertex attributes are legal, but some importers associate
        // multiple custom accessors through an unordered name collection. Encode
        // the VEC2 streams as ordinary, otherwise unused texture-coordinate sets.
        // Light values are normalized so the stream remains conventional UV data.
        float[] encodedLightUvArray = new float[lightUvArray.length];
        for (int i = 0; i < lightUvArray.length; i++) {
            encodedLightUvArray[i] = Math.max(0.0f, Math.min(1.0f, lightUvArray[i] / 240.0f));
        }

        MultiBinaryChunk.Slice lightUvSlice =
            uvChunk.writeFloatArray(encodedLightUvArray, encodedLightUvArray.length);
        int lightUvView = addView(
            gltf, lightUvSlice.bufferIndex(), lightUvSlice.byteOffset(),
            encodedLightUvArray.length * 4, 34962
        );
        int lightUvAcc = addAccessor(gltf, lightUvView, finalVertexCount, "VEC2", 5126, null, null);

        MultiBinaryChunk.Slice midTexCoordSlice =
            uvChunk.writeFloatArray(midTexCoordArray, midTexCoordArray.length);
        int midTexCoordView = addView(
            gltf, midTexCoordSlice.bufferIndex(), midTexCoordSlice.byteOffset(),
            midTexCoordArray.length * 4, 34962
        );
        int midTexCoordAcc =
            addAccessor(gltf, midTexCoordView, finalVertexCount, "VEC2", 5126, null, null);

        MultiBinaryChunk.Slice midBlockSlice =
            chunk.writeFloatArray(midBlockArray, midBlockArray.length);
        int midBlockView = addView(
            gltf, midBlockSlice.bufferIndex(), midBlockSlice.byteOffset(),
            midBlockArray.length * 4, 34962
        );
        int midBlockAcc =
            addAccessor(gltf, midBlockView, finalVertexCount, "VEC4", 5126, null, null);

        int semanticIdAcc = -1;
        if (semanticId != null) {
            float[] semanticIdArray = new float[finalVertexCount * 2];
            for (int i = 0; i < finalVertexCount; i++) {
                semanticIdArray[i * 2] = semanticId;
                semanticIdArray[i * 2 + 1] = 0.0f;
            }
            MultiBinaryChunk.Slice semanticIdSlice =
                uvChunk.writeFloatArray(semanticIdArray, semanticIdArray.length);
            int semanticIdView = addView(
                gltf, semanticIdSlice.bufferIndex(), semanticIdSlice.byteOffset(),
                semanticIdArray.length * 4, 34962
            );
            semanticIdAcc =
                addAccessor(gltf, semanticIdView, finalVertexCount, "VEC2", 5126, null, null);
        }

        MultiBinaryChunk.Slice colorSlice = chunk.writeFloatArray(colorArray, colorArray.length);
        int colorView = addView(gltf, colorSlice.bufferIndex(), colorSlice.byteOffset(), colorArray.length * 4, 34962);
        int colorAcc = addAccessor(gltf, colorView, finalVertexCount, "VEC4", 5126, null, null);

        MultiBinaryChunk.Slice idxSlice = chunk.writeIntArray(indexArray, indexArray.length);
        int idxView = addView(gltf, idxSlice.bufferIndex(), idxSlice.byteOffset(), indexArray.length * 4, 34963);
        int idxAcc = addAccessor(gltf, idxView, finalIndexCount, "SCALAR", 5125, null, null);

        // material
        String sampleSprite = pickPrimarySprite(visualMatKey, matChunk.usedSprites());
        if (sampleSprite == null || !state.getMaterialPaths().containsKey(sampleSprite)) {
            if (state.getMaterialPaths().containsKey("voxelbridge:transparent")) {
                VoxelBridgeLogger.warn(LogModule.TEXTURE, String.format(
                    "[TextureRegistry][MaterialSprites][WARN] matKey=%s picked invalid sprite=%s, fallback to voxelbridge:transparent",
                    matKey, sampleSprite));
                sampleSprite = "voxelbridge:transparent";
            } else {
                throw new IOException("No valid texture path for material " + visualMatKey + " (picked=" + sampleSprite + ")");
            }
        }
        VoxelBridgeLogger.info(LogModule.TEXTURE, String.format(
            "[TextureRegistry][MaterialSprites] matKey=%s sprites=%s picked=%s",
            matKey, matChunk.usedSprites(), sampleSprite));
        int textureIndex = textureRegistry.ensureSpriteTexture(sampleSprite, textures, images);

        Material material = new Material();
        material.setName(visualMatKey);
        MaterialPbrMetallicRoughness pbr = new MaterialPbrMetallicRoughness();
        TextureInfo texInfo = new TextureInfo();
        texInfo.setIndex(textureIndex);
        pbr.setBaseColorTexture(texInfo);
        pbr.setMetallicFactor(0.0f);
        pbr.setRoughnessFactor(1.0f);
        material.setPbrMetallicRoughness(pbr);
        switch (materialRenderLayer) {
            case CUTOUT -> {
                material.setAlphaMode("MASK");
                material.setAlphaCutoff(0.1f);
            }
            case TRANSLUCENT -> material.setAlphaMode("BLEND");
            default -> material.setAlphaMode("OPAQUE");
        }
        if (emissive) {
            material.setEmissiveFactor(new float[] {1.0f, 1.0f, 1.0f});
        }
        boolean forceDoubleSided = com.voxelbridge.config.ExportRuntimeConfig.isExportDoubleSidedEnabled();
        material.setDoubleSided(forceDoubleSided || doubleSided);

        Map<String, Object> extras = new HashMap<>();
        extras.put("voxelbridge:renderLayer", materialRenderLayer.name().toLowerCase(Locale.ROOT));
        extras.put("voxelbridge:emissive", emissive);
        if (semanticId != null) {
            extras.put("voxelbridge:materialIdentity", semanticId);
            for (Map.Entry<String, Object> entry : semanticMap(semantic).entrySet()) {
                extras.put("voxelbridge:" + entry.getKey(), entry.getValue());
            }
        }
        if (!colorMapIndices.isEmpty()) {
            extras.put("voxelbridge:colormapTextures", colorMapIndices);
            extras.put("voxelbridge:colormapUV", 1);
        }
        if (!extras.isEmpty()) material.setExtras(extras);
        Map<String, Object> minecraftMaterial = new LinkedHashMap<>();
        minecraftMaterial.put("renderLayer", materialRenderLayer.name().toLowerCase(Locale.ROOT));
        minecraftMaterial.put("emissive", emissive);
        if (semanticId != null) {
            minecraftMaterial.put("materialIdentity", semanticId);
            minecraftMaterial.putAll(semanticMap(semantic));
            minecraftMaterial.put("identityEncoding", "voxelbridge-scene-material-identity");
        }
        String baseTexturePath = state.getMaterialPaths().get(sampleSprite);
        addLabPbrTexture(
            minecraftMaterial, "normalTexture", baseTexturePath, "n", textures, images
        );
        addLabPbrTexture(
            minecraftMaterial, "specularTexture", baseTexturePath, "s", textures, images
        );
        material.setExtensions(Map.of("VOXELBRIDGE_minecraft_material", minecraftMaterial));
        materials.add(material);
        int matIndex = materials.size() - 1;

        // mesh
        MeshPrimitive prim = new MeshPrimitive();
        Map<String, Integer> attrs = new LinkedHashMap<>();
        attrs.put("POSITION", posAcc);
        attrs.put("NORMAL", normalAcc);
        attrs.put("TANGENT", tangentAcc);
        attrs.put("TEXCOORD_0", uv0Acc);
        attrs.put("TEXCOORD_1", uv1Acc);
        attrs.put("TEXCOORD_2", lightUvAcc);
        attrs.put("TEXCOORD_3", midTexCoordAcc);
        attrs.put("COLOR_0", colorAcc);
        attrs.put("_VOXELBRIDGE_MID_BLOCK", midBlockAcc);
        if (semanticIdAcc >= 0) {
            attrs.put("TEXCOORD_4", semanticIdAcc);
        }
        prim.setAttributes(attrs);
        prim.setIndices(idxAcc);
        prim.setMaterial(matIndex);
        prim.setMode(4);
        if (semanticId != null) {
            prim.setExtensions(Map.of(
                "VOXELBRIDGE_minecraft_material",
                Map.of("materialIdentity", semanticId)
            ));
        }

        Mesh mesh = new Mesh();
        mesh.setName(visualMatKey);
        mesh.setPrimitives(Collections.singletonList(prim));
        meshes.add(mesh);

        Node node = new Node();
        node.setName(visualMatKey);
        node.setMesh(meshes.size() - 1);
        nodes.add(node);
    }

    private static int renderLayerPriority(RenderLayer layer) {
        return switch (layer) {
            case TRANSLUCENT -> 3;
            case CUTOUT -> 2;
            case SOLID -> 1;
            case UNKNOWN -> 0;
        };
    }

    private Map<QuadSemantic, Integer> buildSemanticDictionary(
        Map<String, Object> sceneContract
    ) {
        List<QuadSemantic> identities = bucketSemantics.values().stream()
            .filter(Objects::nonNull)
            .filter(semantic -> !semantic.isEmpty())
            .distinct()
            .sorted(Comparator.comparing(QuadSemantic::stableKey))
            .toList();
        Map<QuadSemantic, Integer> ids = new HashMap<>();
        List<Map<String, Object>> encoded = new ArrayList<>(identities.size());
        for (int index = 0; index < identities.size(); index++) {
            QuadSemantic semantic = identities.get(index);
            ids.put(semantic, index);
            Map<String, Object> entry = semanticMap(semantic);
            entry.put("id", index);
            encoded.add(entry);
        }
        sceneContract.put("materialIdentities", encoded);
        sceneContract.put("propertyDomains", Map.of(
            "block.properties", List.of("blockId", "blockState"),
            "entity.properties", List.of("entityType"),
            "item.properties", List.of("itemId")
        ));
        return ids;
    }

    private static Map<String, Object> semanticMap(QuadSemantic semantic) {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "objectClass", semantic.objectClass());
        putIfPresent(result, "materialKey", semantic.materialKey());
        putIfPresent(result, "blockId", semantic.blockId());
        putIfPresent(result, "blockState", semantic.blockState());
        putIfPresent(result, "entityType", semantic.entityType());
        putIfPresent(result, "blockEntityId", semantic.blockEntityId());
        putIfPresent(result, "itemId", semantic.itemId());
        return result;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Build glTF tangents from the final positions and atlas-remapped UVs.
     *
     * <p>The XYZ components are orthogonalized against NORMAL. W is the
     * bitangent handedness expected by glTF and Iris' at_tangent input.</p>
     */
    static float[] computeTangents(
        float[] positions,
        float[] uv0,
        float[] normals,
        int[] indices
    ) {
        int vertexCount = positions.length / 3;
        float[] tangentSums = new float[vertexCount * 3];
        float[] bitangentSums = new float[vertexCount * 3];
        for (int index = 0; index + 2 < indices.length; index += 3) {
            int i0 = indices[index];
            int i1 = indices[index + 1];
            int i2 = indices[index + 2];
            if (i0 < 0 || i1 < 0 || i2 < 0
                    || i0 >= vertexCount || i1 >= vertexCount || i2 >= vertexCount) {
                continue;
            }
            int p0 = i0 * 3, p1 = i1 * 3, p2 = i2 * 3;
            int t0 = i0 * 2, t1 = i1 * 2, t2 = i2 * 2;
            float e1x = positions[p1] - positions[p0];
            float e1y = positions[p1 + 1] - positions[p0 + 1];
            float e1z = positions[p1 + 2] - positions[p0 + 2];
            float e2x = positions[p2] - positions[p0];
            float e2y = positions[p2 + 1] - positions[p0 + 1];
            float e2z = positions[p2 + 2] - positions[p0 + 2];
            float du1 = uv0[t1] - uv0[t0];
            float dv1 = uv0[t1 + 1] - uv0[t0 + 1];
            float du2 = uv0[t2] - uv0[t0];
            float dv2 = uv0[t2 + 1] - uv0[t0 + 1];
            float determinant = du1 * dv2 - dv1 * du2;
            if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-12f) {
                continue;
            }
            float reciprocal = 1.0f / determinant;
            float tx = (e1x * dv2 - e2x * dv1) * reciprocal;
            float ty = (e1y * dv2 - e2y * dv1) * reciprocal;
            float tz = (e1z * dv2 - e2z * dv1) * reciprocal;
            float bx = (e2x * du1 - e1x * du2) * reciprocal;
            float by = (e2y * du1 - e1y * du2) * reciprocal;
            float bz = (e2z * du1 - e1z * du2) * reciprocal;
            for (int corner = 0; corner < 3; corner++) {
                int vertex = corner == 0 ? i0 : (corner == 1 ? i1 : i2);
                int base = vertex * 3;
                tangentSums[base] += tx;
                tangentSums[base + 1] += ty;
                tangentSums[base + 2] += tz;
                bitangentSums[base] += bx;
                bitangentSums[base + 1] += by;
                bitangentSums[base + 2] += bz;
            }
        }

        float[] result = new float[vertexCount * 4];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int normalBase = vertex * 3;
            float nx = normals[normalBase];
            float ny = normals[normalBase + 1];
            float nz = normals[normalBase + 2];
            float normalLength = length(nx, ny, nz);
            if (!(normalLength > 1.0e-12f)) {
                nx = 0f; ny = 1f; nz = 0f;
            } else {
                nx /= normalLength; ny /= normalLength; nz /= normalLength;
            }

            float tx = tangentSums[normalBase];
            float ty = tangentSums[normalBase + 1];
            float tz = tangentSums[normalBase + 2];
            float normalDotTangent = nx * tx + ny * ty + nz * tz;
            tx -= nx * normalDotTangent;
            ty -= ny * normalDotTangent;
            tz -= nz * normalDotTangent;
            float tangentLength = length(tx, ty, tz);
            if (!(tangentLength > 1.0e-12f)) {
                // Choose the least-parallel cardinal axis for a stable fallback.
                float ax = Math.abs(nx) < 0.8f ? 1f : 0f;
                float ay = Math.abs(nx) < 0.8f ? 0f : 1f;
                float az = 0f;
                float axisDotNormal = ax * nx + ay * ny + az * nz;
                tx = ax - nx * axisDotNormal;
                ty = ay - ny * axisDotNormal;
                tz = az - nz * axisDotNormal;
                tangentLength = length(tx, ty, tz);
            }
            tx /= tangentLength;
            ty /= tangentLength;
            tz /= tangentLength;

            float bx = bitangentSums[normalBase];
            float by = bitangentSums[normalBase + 1];
            float bz = bitangentSums[normalBase + 2];
            float crossX = ny * tz - nz * ty;
            float crossY = nz * tx - nx * tz;
            float crossZ = nx * ty - ny * tx;
            float handedness = crossX * bx + crossY * by + crossZ * bz < 0f
                ? -1f : 1f;
            int output = vertex * 4;
            result[output] = tx;
            result[output + 1] = ty;
            result[output + 2] = tz;
            result[output + 3] = handedness;
        }
        return result;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private void addLabPbrTexture(
        Map<String, Object> extension,
        String field,
        String basePath,
        String suffix,
        List<Texture> textures,
        List<Image> images
    ) {
        String relativePath = companionTexturePath(basePath, suffix);
        if (relativePath == null || !Files.isRegularFile(outputDir.resolve(relativePath))) {
            return;
        }
        extension.put(field, textureRegistry.ensurePathTexture(relativePath, textures, images));
    }

    private static String companionTexturePath(String basePath, String suffix) {
        if (basePath == null || !basePath.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return null;
        }
        int slash = Math.max(basePath.lastIndexOf('/'), basePath.lastIndexOf('\\'));
        String directory = slash >= 0 ? basePath.substring(0, slash + 1) : "";
        String file = slash >= 0 ? basePath.substring(slash + 1) : basePath;
        int extension = file.length() - 4;
        int udimSeparator = file.lastIndexOf('_', extension - 1);
        if (udimSeparator >= 0) {
            String tail = file.substring(udimSeparator + 1, extension);
            if (tail.length() == 4 && tail.chars().allMatch(Character::isDigit)) {
                return directory + file.substring(0, udimSeparator + 1)
                    + suffix + "_" + tail + ".png";
            }
        }
        return directory + file.substring(0, extension) + "_" + suffix + ".png";
    }

    /**
     * Pick a stable primary sprite for a material: prefer entity:* sprites, otherwise first sorted.
     */
    private String pickPrimarySprite(String matKey, Set<String> usedSprites) {
        if (usedSprites == null || usedSprites.isEmpty()) {
            return null;
        }
        List<String> withMaterialPath = new ArrayList<>();
        for (String s : usedSprites) {
            if (s != null && state.getMaterialPaths().containsKey(s)) {
                withMaterialPath.add(s);
            }
        }
        List<String> candidates = withMaterialPath.isEmpty()
            ? new ArrayList<>(usedSprites)
            : withMaterialPath;

        if (matKey != null && matKey.endsWith("_animated")) {
            for (String s : candidates) {
                if (matKey.equals(com.voxelbridge.export.texture.TexturePathResolver.animationBaseName(s))) {
                    return s;
                }
            }
        }
        List<String> list = new ArrayList<>(candidates);
        list.remove("voxelbridge:transparent");
        if (list.isEmpty()) {
            list = new ArrayList<>(candidates);
        }
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
        if (!options.animationEnabled()) {
            return null;
        }
        if (spriteKey == null) return null;
        var repo = state.getTextureRepository();
        if (!repo.hasAnimation(spriteKey)) return null;
        return com.voxelbridge.export.texture.TexturePathResolver.animationBaseName(spriteKey);
    }

    private String resolveBucketKey(String materialKey, String spriteKey) {
        if (spriteKey == null) {
            return materialKey;
        }

        String animName = resolveAnimationName(spriteKey);
        if (animName != null) {
            return resolveAnimatedBucketKey(materialKey, animName);
        }

        // In INDIVIDUAL mode each sprite is a separate image file, so each unique
        // sprite must land in its own bucket — otherwise pickPrimarySprite selects
        // one texture and every other sprite's quads render with the wrong image.
        // In ATLAS mode all sprites share one packed atlas, so merging is fine.
        if (options.atlasMode() == com.voxelbridge.export.texture.ExportOptions.AtlasMode.INDIVIDUAL) {
            if (!"voxelbridge:transparent".equals(spriteKey)) {
                String base = materialKey != null ? materialKey : "material";
                return base + "__" + com.voxelbridge.export.texture.TexturePathResolver.safe(spriteKey);
            }
        }

        return materialKey;
    }

    private QuadSemantic exportSemantic(QuadSemantic semantic) {
        if (com.voxelbridge.config.ExportRuntimeConfig.getMaterialIdentityMode()
                == com.voxelbridge.config.ExportRuntimeConfig.MaterialIdentityMode.NONE) {
            return QuadSemantic.NONE;
        }
        return semantic != null ? semantic : QuadSemantic.NONE;
    }

    private String resolveSemanticBucketKey(
        String materialKey,
        String spriteKey,
        QuadSemantic semantic
    ) {
        String visualBucketKey = resolveBucketKey(materialKey, spriteKey);
        QuadSemantic exported = exportSemantic(semantic);
        if (exported.isEmpty()) {
            bucketVisualMaterialKeys.putIfAbsent(visualBucketKey, visualBucketKey);
            return visualBucketKey;
        }
        int internalSemanticId = internalSemanticIds.computeIfAbsent(
            exported, ignored -> nextInternalSemanticId.getAndIncrement()
        );
        String bucketKey = visualBucketKey + '\u001e' + internalSemanticId;
        bucketVisualMaterialKeys.putIfAbsent(bucketKey, visualBucketKey);
        bucketSemantics.putIfAbsent(bucketKey, exported);
        return bucketKey;
    }

    private String resolveAnimatedBucketKey(String materialKey, String animName) {
        if (materialKey == null) {
            return animName;
        }
        if (materialKey.endsWith("_animated")) {
            return materialKey;
        }

        java.util.ArrayList<String> suffixes = new java.util.ArrayList<>(3);
        String base = materialKey;
        boolean stripped;
        do {
            stripped = false;
            if (base.endsWith("_overlay")) {
                suffixes.add("overlay");
                base = base.substring(0, base.length() - "_overlay".length());
                stripped = true;
            } else if (base.endsWith("_hilight")) {
                suffixes.add("hilight");
                base = base.substring(0, base.length() - "_hilight".length());
                stripped = true;
            } else if (base.endsWith("_emissive")) {
                suffixes.add("emissive");
                base = base.substring(0, base.length() - "_emissive".length());
                stripped = true;
            }
        } while (stripped);

        if (suffixes.isEmpty()) {
            return animName;
        }

        String animSuffix = "_animated";
        String animBase = animName.endsWith(animSuffix)
            ? animName.substring(0, animName.length() - animSuffix.length())
            : animName;

        java.util.Collections.reverse(suffixes);
        String mergedSuffix = String.join("_", suffixes);
        return animBase + "_" + mergedSuffix + animSuffix;
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

    public enum Stage {
        SAMPLING,
        ATLAS,
        FINALIZE
    }

    public interface ProgressReporter {
        ProgressReporter NOOP = new ProgressReporter() {
            @Override
            public void setStage(Stage stage, String detail) {}

            @Override
            public void setPhasePercent(Float percent) {}
        };

        void setStage(Stage stage, String detail);

        void setPhasePercent(Float percent);
    }
}





