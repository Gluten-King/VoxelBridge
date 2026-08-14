package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.core.ir.*;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.core.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.ExportOptions;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import de.javagl.jgltf.impl.v2.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

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
    private final GltfMaterialWriter materialWriter;
    private final GltfPrimitiveAssembler primitiveAssembler;
    private final ProgressReporter progressReporter;
    private final ExportOptions options;
    private static final int BYTES_PER_QUAD_GEOMETRY = 140;
    private static final int BYTES_PER_QUAD_UV = 64;

    // Streaming writer
    private final StreamingGeometryWriter streamingWriter;
    private final GltfBinarySpool binarySpool;
    private final SpriteIndex spriteIndex;
    private final GeometryIndex geometryIndex;

    public GltfSceneBuilder(ExportState state,
                            Path outDir,
                            ProgressReporter progressReporter,
                            ExportOptions options) throws IOException {
        this.state = state;
        this.outputDir = outDir;
        this.textureRegistry = new TextureRegistry(state);
        this.progressReporter = progressReporter != null ? progressReporter : ProgressReporter.NOOP;
        this.options = Objects.requireNonNull(options, "options");
        this.materialWriter = new GltfMaterialWriter(state, textureRegistry, this.options);

        // Create streaming indices
        this.spriteIndex = new SpriteIndex();
        this.geometryIndex = new GeometryIndex();
        this.primitiveAssembler = new GltfPrimitiveAssembler(
            state, this.options, spriteIndex, materialWriter);

        // Create streaming writer (Single Temp File)
        Path geometryBin = outDir.resolve("geometry.bin");
        // Pass null for unused UV path (signature kept for compatibility if needed, but we ignore it)
        this.streamingWriter = new StreamingGeometryWriter(geometryBin, null, spriteIndex, geometryIndex);
        this.binarySpool = new GltfBinarySpool(
            state, this.options, streamingWriter, this::resolveBucketKey);

        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Initialized streaming geometry pipeline (Paged)");
    }

    /**
     * Optimized batch addition.
     * Called by ChunkDeduplicator to reduce queue lock contention.
     */
    @Override
    public void addBatch(String materialGroupKey,
                         List<String> spriteKeys,
                         List<String> overlaySpriteKeys,
                         float[] flatPositions,
                         float[] flatUv0s,
                         float[] flatUv1s,
                         float[] flatNormals,
                         float[] flatColors,
                         int[] quadFlags) {
        
        if (materialGroupKey == null || spriteKeys.isEmpty()) return;
        
        binarySpool.addBatch(
            materialGroupKey,
            spriteKeys, overlaySpriteKeys,
            flatPositions, flatUv0s, flatUv1s, flatNormals, flatColors,
            quadFlags
        );
    }

    @Override
    public void addQuad(String materialKey,
                        String spriteKey,
                        String overlaySpriteKey,
                        RenderLayer renderLayer,
                        TintMode tintMode,
                        boolean doubleSided,
                        boolean emissive,
                        float[] positions,
                        float[] uv0,
                        float[] uv1,
                        float[] normal,
                        float[] colors) {
        if (materialKey == null || spriteKey == null) return;
        int quadFlags = IrFlags.encode(renderLayer, tintMode, doubleSided, emissive);
        String bucketKey = resolveBucketKey(materialKey, spriteKey);

        binarySpool.addQuad(
            bucketKey, spriteKey, overlaySpriteKey, quadFlags,
            positions, uv0, uv1, normal, colors);
    }

    public Path write(SceneWriteRequest request) throws IOException {
        try {
            // 1. 
            progressReporter.setStage(Stage.SAMPLING, "Sampling complete");
            progressReporter.setPhasePercent(null);
            VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Stage 1/3: Finalizing sampling...");
            long tFinalizeSampling = VoxelBridgeLogger.now();

            binarySpool.finish();

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

    /**
     * Assembles the final glTF asset by reading binary data and creating accessors/views.
     */
    private Path assembleGltf(SceneWriteRequest request, Path geometryBin, PhaseProgress phase) throws IOException {
        VoxelBridgeLogger.info(LogModule.GLTF, "[GltfBuilder] Starting glTF assembly...");
        VoxelBridgeLogger.memory("before_gltf_assembly");

        try {
            GlTF gltf = GltfDocumentAssembler.createDocument();
            GltfAccessorWriter accessorWriter = new GltfAccessorWriter(gltf);

        Path binPath = request.outputDir().resolve(request.baseName() + ".bin");
        Path uvBinPath = request.outputDir().resolve(request.baseName() + ".uv.bin");

        // Thread-safe lists for parallel material assembly
        List<Material> materials = Collections.synchronizedList(new ArrayList<>());
        List<Mesh> meshes = Collections.synchronizedList(new ArrayList<>());
        List<Node> nodes = Collections.synchronizedList(new ArrayList<>());
        // Make texture/image lists thread-safe; material assembly runs in parallel
        List<Texture> textures = Collections.synchronizedList(new ArrayList<>());
            List<Image> images = Collections.synchronizedList(new ArrayList<>());
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
                try (BinarySpoolReader mappedReader = new BinarySpoolReader(geometryChannel)) {
                    
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
                            primitiveAssembler.assemble(
                                matKey, matChunk,
                                mappedReader, // Pass mapped reader instead of channel
                                accessorWriter, chunk, uvChunk,
                                materials, meshes, nodes, textures, images, colorMapIndices
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
                GltfDocumentAssembler.attachScene(gltf, materials, meshes, nodes, textures, images);

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
                Path gltfPath = request.outputDir().resolve(request.baseName() + ".gltf");
                long tWriteGltf = VoxelBridgeLogger.now();
                GltfDocumentAssembler.write(gltf, gltfPath);
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





