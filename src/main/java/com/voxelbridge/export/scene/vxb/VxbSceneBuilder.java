package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.export.texture.AnimatedTextureHelper;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.util.client.ProgressNotifier;
import com.voxelbridge.util.debug.ExportLogger;
import com.voxelbridge.util.debug.TimeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VXB scene builder that writes split binary buffers with uv loops.
 */
public final class VxbSceneBuilder implements SceneSink {
    private static final int ALIGNMENT = 16;
    private static final int NO_SPRITE_ID = 65535;

    private final ExportContext ctx;
    private final Path outputDir;
    private final String baseName;
    private final Map<String, Integer> spriteIds = new LinkedHashMap<>();
    private final List<String> spriteKeys = new ArrayList<>();
    private final List<SpriteSize> spriteSizes = new ArrayList<>();
    private final Map<Long, ChunkState> chunkStates = new LinkedHashMap<>();
    private final Map<String, MeshInfo> meshInfos = new LinkedHashMap<>(); // bucketKey -> MeshInfo
    private final ThreadLocal<Long> currentChunk = new ThreadLocal<>();
    private final Object writeLock = new Object();
    private final BlockingQueue<QueueEvent> queue = new ArrayBlockingQueue<>(16384);
    private final AtomicBoolean writerStarted = new AtomicBoolean(false);
    private Thread writerThread;
    private final BinaryWriter binOut;
    private final BinaryWriter uvRawOut;
    private final BufferInfo bin;
    private final BufferInfo uv;
    private final Path uvRawPath;
    private static final int BUFFER_SIZE = 32 << 20; // 32MB，大缓冲减少 IO 次数

    public VxbSceneBuilder(ExportContext ctx, Path outputDir, String baseName) throws IOException {
        this.ctx = ctx;
        this.outputDir = outputDir;
        this.baseName = baseName;
        Files.createDirectories(outputDir);
        Path binPath = outputDir.resolve(baseName + ".bin");
        this.uvRawPath = outputDir.resolve(baseName + ".uvraw.bin");
        this.bin = new BufferInfo("bin", binPath);
        this.uv = new BufferInfo("uv", outputDir.resolve(baseName + ".uv.bin"));
        this.binOut = new BinaryWriter(new BufferedOutputStream(Files.newOutputStream(binPath), BUFFER_SIZE));
        this.uvRawOut = new BinaryWriter(new BufferedOutputStream(Files.newOutputStream(uvRawPath), BUFFER_SIZE));
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
        if (materialGroupKey == null || spriteKey == null || positions == null || uv0 == null) return;
        startWriterThread();
        long chunkKey = currentChunk.get() != null ? currentChunk.get() : -1L;
        String animName = resolveAnimationName(spriteKey);
        enqueue(new QuadEvent(chunkKey, materialGroupKey, spriteKey, overlaySpriteKey, positions, uv0, uv1, normal, colors, doubleSided, animName));
    }

    @Override
    public void onChunkStart(int chunkX, int chunkZ) {
        long key = packChunkKey(chunkX, chunkZ);
        currentChunk.set(key);
        startWriterThread();
        enqueue(new ChunkStartEvent(key));
    }

    @Override
    public void onChunkEnd(int chunkX, int chunkZ, boolean successful) {
        long key = packChunkKey(chunkX, chunkZ);
        currentChunk.remove();
        startWriterThread();
        enqueue(new ChunkEndEvent(key, successful));
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        Minecraft mc = ctx.getMc();
        long tWrite = TimeLogger.now();
        // Signal writer thread to finish and wait for completion
        long tFinalizeSampling = TimeLogger.now();
        startWriterThread();
        enqueue(PoisonEvent.INSTANCE);
        joinWriter();
        closeWriters();
        TimeLogger.logDuration("vxb_finalize_sampling", TimeLogger.elapsedSince(tFinalizeSampling));

        Path uvPath = uv.path;
        Path jsonPath = outputDir.resolve(baseName + ".vxb");
        List<MeshInfo> meshList = new ArrayList<>(meshInfos.values());

        TimeLogger.logMemory("before_vxb_uv_remap");
        PhaseProgress phase = new PhaseProgress();
        long tUvRemap = TimeLogger.now();
        ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "Remapping UVs");
        ExportProgressTracker.setPhasePercent(0.0f);
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
        VxbUvRemapper.remapUv(ctx, spriteKeys, spriteSizes, meshList, uvRawPath, uvPath, frac -> {
            float mapped = (float) (0.6f * Math.max(0d, Math.min(1d, frac)));
            if (phase.shouldPush(mapped)) {
                ExportProgressTracker.setPhasePercent(mapped);
                ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
            }
        });
        Files.deleteIfExists(uvRawPath);
        TimeLogger.logDuration("vxb_uv_remap", TimeLogger.elapsedSince(tUvRemap));
        ExportProgressTracker.setPhasePercent(0.6f);
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());

        long tAssembly = TimeLogger.now();
        ExportProgressTracker.setStage(ExportProgressTracker.Stage.FINALIZE, "Assembling VXB");
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
        int totalSections = meshList.stream().mapToInt(m -> m.sections.size()).sum();
        writeJson(jsonPath, request, bin, uv, meshList, phase, totalSections, mc);
        TimeLogger.logDuration("vxb_write_json", TimeLogger.elapsedSince(tAssembly));
        TimeLogger.logDuration("vxb_assembly", TimeLogger.elapsedSince(tAssembly));
        TimeLogger.logDuration("vxb_write", TimeLogger.elapsedSince(tWrite));
        ExportProgressTracker.setPhasePercent(1.0f);
        ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
        return jsonPath;
    }

    private void closeWriters() throws IOException {
        synchronized (writeLock) {
            binOut.close();
            uvRawOut.close();
        }
    }

    private ChunkState getOrCreateChunkState(long key) {
        synchronized (chunkStates) {
            return chunkStates.computeIfAbsent(key, k -> new ChunkState());
        }
    }

    private void flushChunk(ChunkState chunkState) {
        if (chunkState.meshes.isEmpty()) {
            return;
        }
        synchronized (writeLock) {
            for (MeshBuilder mesh : chunkState.meshes.values()) {
                SectionInfo section;
                try {
                    section = mesh.write(binOut, uvRawOut, uvRawOut, binOut, binOut, binOut, binOut,
                        bin, uv, uv, bin, bin, bin, bin);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to flush VXB chunk section", e);
                }
                MeshInfo info = meshInfos.computeIfAbsent(mesh.bucketKey,
                    k -> new MeshInfo(mesh.materialKey, mesh.animationName));
                info.addSection(section, mesh.animationName);
            }
        }
    }

    private void startWriterThread() {
        if (writerStarted.getAndSet(true)) return;
        writerThread = new Thread(() -> {
            try {
                while (true) {
                    QueueEvent ev = queue.take();
                    if (ev == PoisonEvent.INSTANCE) {
                        chunkStates.clear();
                        break;
                    }
                    if (ev instanceof ChunkStartEvent cse) {
                        getOrCreateChunkState(cse.chunkKey());
                    } else if (ev instanceof ChunkEndEvent cee) {
                        ChunkState state = chunkStates.remove(cee.chunkKey());
                        if (state != null && cee.successful()) {
                            flushChunk(state);
                        }
                    } else if (ev instanceof QuadEvent qe) {
                        ChunkState state = getOrCreateChunkState(qe.chunkKey());
                        String bucketKey = bucketKey(qe.materialGroupKey(), qe.animationName());
                        MeshBuilder mesh = state.meshes.computeIfAbsent(bucketKey,
                            k -> new MeshBuilder(k, qe.materialGroupKey(), qe.animationName()));
                        mesh.recordQuad(qe.spriteKey(), qe.overlaySpriteKey(), qe.positions(), qe.uv0(), qe.uv1(),
                            qe.normal(), qe.colors(), qe.doubleSided(), this::getSpriteId, this::getSpriteSize);
                    }
                }
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }, "VXB-StreamingWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void enqueue(QueueEvent ev) {
        try {
            queue.put(ev);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinWriter() {
        if (writerThread == null) return;
        try {
            writerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private interface QueueEvent {}
    private record QuadEvent(long chunkKey,
                             String materialGroupKey,
                             String spriteKey,
                             String overlaySpriteKey,
                             float[] positions,
                             float[] uv0,
                             float[] uv1,
                             float[] normal,
                             float[] colors,
                             boolean doubleSided,
                             String animationName) implements QueueEvent {}
    private record ChunkStartEvent(long chunkKey) implements QueueEvent {}
    private record ChunkEndEvent(long chunkKey, boolean successful) implements QueueEvent {}
    private enum PoisonEvent implements QueueEvent { INSTANCE }

    private long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private String bucketKey(String materialKey, String animationName) {
        return animationName == null ? materialKey : animationName;
    }

    private synchronized int getSpriteId(String spriteKey) {
        return spriteIds.computeIfAbsent(spriteKey, key -> {
            spriteKeys.add(key);
            spriteSizes.add(getSpriteSize(key));
            return spriteKeys.size() - 1;
        });
    }

    private SpriteSize getSpriteSize(String spriteKey) {
        BufferedImage img = ctx.getCachedSpriteImage(spriteKey);
        if (img == null && !spriteKey.contains("textures/atlas/")) {
            try {
                var loc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
                img = TextureLoader.readTexture(loc);
                if (img != null) {
                    ctx.cacheSpriteImage(spriteKey, img);
                }
            } catch (Exception ignored) {
                return new SpriteSize(16, 16);
            }
        }
        if (img == null) {
            return new SpriteSize(16, 16);
        }
        return new SpriteSize(img.getWidth(), img.getHeight());
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
            // Try atlas sprite first (if already loaded)
            TextureAtlas atlas = ctx.getMc().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            ResourceLocation spriteLoc = ResourceLocation.parse(spriteKey);
            TextureAtlasSprite sprite = atlas.getSprite(spriteLoc);
            if (sprite != null) {
                AnimatedTextureHelper.extractFromSprite(spriteKey, sprite, repo);
                if (repo.hasAnimation(spriteKey)) return;
            }
        } catch (Exception ignored) {
            // Fallback to metadata scan below
        }
        try {
            ResourceLocation texLoc = TextureLoader.spriteKeyToTexturePNG(spriteKey);
            AnimatedTextureHelper.detectFromMetadata(spriteKey, texLoc, repo);
        } catch (Exception e) {
            ExportLogger.logAnimation("[Animation][WARN] Detection failed for " + spriteKey + ": " + e.getMessage());
        }
    }

    private void writeJson(Path jsonPath,
                           SceneWriteRequest request,
                           BufferInfo bin,
                           BufferInfo uv,
                           List<MeshInfo> meshInfos,
                           PhaseProgress phase,
                           int totalSections,
                           Minecraft mc) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(jsonPath, StandardCharsets.UTF_8)) {
            out.write("{\n");
            out.write("  \"version\": 1,\n");
            out.write("  \"endian\": \"LE\",\n");
            out.write("  \"atlasSize\": " + ExportRuntimeConfig.getAtlasSize().getSize() + ",\n");
            out.write("  \"colorMode\": \"" + ExportRuntimeConfig.getColorMode().name() + "\",\n");
            String uv1Quant = ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP
                ? "normalized_u16"
                : "atlas_u16";
            out.write("  \"uv1Quantization\": \"" + uv1Quant + "\",\n");
            writeColormapTextures(out);
            out.write("  \"buffers\": [\n");
            writeBuffer(out, bin, 0, 4);
            out.write(",\n");
            writeBuffer(out, uv, 1, 4);
            out.write("\n  ],\n");

            out.write("  \"sprites\": [\n");
            for (int i = 0; i < spriteKeys.size(); i++) {
                String key = spriteKeys.get(i);
                SpriteMeta meta = buildSpriteMeta(key);
                out.write("    {\n");
                out.write("      \"id\": " + i + ",\n");
                out.write("      \"key\": \"" + escape(key) + "\",\n");
                out.write("      \"width\": " + meta.width + ",\n");
                out.write("      \"height\": " + meta.height + ",\n");
                if (meta.texturePath != null) {
                    out.write("      \"texture\": \"" + escape(meta.texturePath) + "\",\n");
                } else {
                    out.write("      \"texture\": null,\n");
                }
                if (meta.atlas != null) {
                    out.write("      \"atlas\": {\n");
                    out.write("        \"page\": " + meta.atlas.page + ",\n");
                    out.write("        \"x\": " + meta.atlas.x + ",\n");
                    out.write("        \"y\": " + meta.atlas.y + ",\n");
                    out.write("        \"w\": " + meta.atlas.w + ",\n");
                    out.write("        \"h\": " + meta.atlas.h + "\n");
                    out.write("      }\n");
                } else {
                    out.write("      \"atlas\": null\n");
                }
                out.write("      ,\"animation\": " + (meta.animationBase != null ? "\"" + escape(meta.animationBase) + "\"" : "null") + "\n");
                out.write("    }" + (i + 1 < spriteKeys.size() ? "," : "") + "\n");
            }
            out.write("  ],\n");

            out.write("  \"meshes\": [\n");
            int processedSections = 0;
            for (int i = 0; i < meshInfos.size(); i++) {
            MeshInfo m = meshInfos.get(i);
            String name = m.animationName != null ? m.animationName : m.materialKey;
            out.write("    {\n");
            out.write("      \"name\": \"" + escape(name) + "\",\n");
                out.write("      \"vertexCount\": " + m.vertexCount + ",\n");
                out.write("      \"indexCount\": " + m.indexCount + ",\n");
                out.write("      \"faceCount\": " + m.faceCount + ",\n");
                out.write("      \"faceIndexCount\": " + m.faceIndexCount + ",\n");
                out.write("      \"doubleSided\": " + m.doubleSided + ",\n");
                out.write("      \"sections\": [\n");
                for (int s = 0; s < m.sections.size(); s++) {
                    SectionInfo sec = m.sections.get(s);
                    out.write("        {\n");
                    out.write("          \"vertexCount\": " + sec.vertexCount + ",\n");
                    out.write("          \"indexCount\": " + sec.indexCount + ",\n");
                    out.write("          \"faceCount\": " + sec.faceCount + ",\n");
                    out.write("          \"faceIndexCount\": " + sec.faceIndexCount + ",\n");
                    out.write("          \"doubleSided\": " + sec.doubleSided + ",\n");
                    out.write("          \"views\": {\n");
                    out.write("            \"POSITION\": {\"buffer\": \"bin\", \"offset\": " + sec.geoOffset + ", \"stride\": 12, \"type\": \"f32x3\"},\n");
                    out.write("            \"INDEX\": {\"buffer\": \"bin\", \"offset\": " + sec.idxOffset + ", \"stride\": 4, \"type\": \"u32\"},\n");
                    out.write("            \"UV_LOOP\": {\"buffer\": \"uv\", \"offset\": " + sec.uvOffset + ", \"stride\": 8, \"type\": \"u16x4\"},\n");
                    out.write("            \"UV1_LOOP\": {\"buffer\": \"uv\", \"offset\": " + sec.uv1Offset + ", \"stride\": 8, \"type\": \"u16x4\"},\n");
                    out.write("            \"LOOP_ATTR\": {\"buffer\": \"bin\", \"offset\": " + sec.loopOffset + ", \"stride\": 16, \"type\": \"f32x3_u8x4\"},\n");
                    out.write("            \"FACE_COUNT\": {\"buffer\": \"bin\", \"offset\": " + sec.faceCountOffset + ", \"stride\": 2, \"type\": \"u16\"},\n");
                    out.write("            \"FACE_INDEX\": {\"buffer\": \"bin\", \"offset\": " + sec.faceIndexOffset + ", \"stride\": 4, \"type\": \"u32\"}\n");
                    out.write("          }\n");
                    out.write("        }" + (s + 1 < m.sections.size() ? "," : "") + "\n");
                    if (totalSections > 0) {
                        float frac = (++processedSections) / (float) totalSections;
                        float mapped = 0.6f + 0.4f * frac;
                        if (phase.shouldPush(mapped)) {
                            ExportProgressTracker.setPhasePercent(mapped);
                            ProgressNotifier.showDetailed(mc, ExportProgressTracker.progress());
                        }
                    }
                }
                out.write("      ]\n");
                out.write("    }" + (i + 1 < meshInfos.size() ? "," : "") + "\n");
            }
            out.write("  ],\n");
            writeNodes(out, meshInfos);
            out.write("}\n");
        }
    }

    private void writeColormapTextures(BufferedWriter out) throws IOException {
        List<String> textures = listColormapTextures();
        out.write("  \"colormapTextures\": [");
        for (int i = 0; i < textures.size(); i++) {
            out.write("\"" + escape(textures.get(i)) + "\"");
            if (i + 1 < textures.size()) {
                out.write(", ");
            }
        }
        out.write("],\n");
    }

    private List<String> listColormapTextures() throws IOException {
        Path dir = outputDir.resolve("textures").resolve("colormap");
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("colormap_"))
                .sorted()
                .map(p -> "textures/colormap/" + p.getFileName().toString())
                .toList();
        }
    }

    private void writeNodes(BufferedWriter out, List<MeshInfo> meshInfos) throws IOException {
        out.write("  \"nodes\": [\n");
        for (int i = 0; i < meshInfos.size(); i++) {
            MeshInfo m = meshInfos.get(i);
            String name = m.animationName != null ? m.animationName : m.materialKey;
            out.write("    {\"id\": " + i + ", \"name\": \"" + escape(name) + "\", \"mesh\": " + i + "}");
            if (i + 1 < meshInfos.size()) {
                out.write(",");
            }
            out.write("\n");
        }
        out.write("  ],\n");

        out.write("  \"scenes\": [\n");
        out.write("    {\"nodes\": [");
        for (int i = 0; i < meshInfos.size(); i++) {
            out.write(Integer.toString(i));
            if (i + 1 < meshInfos.size()) {
                out.write(", ");
            }
        }
        out.write("]}\n");
        out.write("  ]\n");
    }

    private SpriteMeta buildSpriteMeta(String spriteKey) {
        SpriteSize size = getSpriteSize(spriteKey);
        String texturePath = ctx.getMaterialPaths().get(spriteKey);
        ExportContext.TintAtlas atlas = ctx.getAtlasBook().get(spriteKey);
        AtlasPlacement placement = null;
        if (atlas != null) {
            ExportContext.TexturePlacement p = atlas.placements.getOrDefault(0,
                atlas.placements.values().stream().findFirst().orElse(null));
            if (p != null) {
                placement = new AtlasPlacement(p.page(), p.x(), p.y(), p.w(), p.h());
            }
        }
        String animationBase = animationBaseName(spriteKey);
        return new SpriteMeta(size.width, size.height, texturePath, placement, animationBase);
    }

    private String animationBaseName(String spriteKey) {
        if (!ExportRuntimeConfig.isAnimationEnabled()) {
            return null;
        }
        if (!ctx.getTextureRepository().hasAnimation(spriteKey)) {
            return null;
        }
        String base = safe(spriteKey);
        if (base.endsWith("_animated")) {
            base = base.substring(0, base.length() - "_animated".length());
        }
        boolean overlay = spriteKey.endsWith("_overlay") || spriteKey.contains("/overlay") || spriteKey.contains(":overlay");
        if (overlay && !base.endsWith("_overlay")) {
            base = base + "_overlay";
        }
        return base + "_animated";
    }

    private static String safe(String spriteKey) {
        if (spriteKey == null) {
            return "unknown";
        }
        return spriteKey.replace(':', '_').replace('/', '_');
    }

    private void writeBuffer(BufferedWriter out, BufferInfo buffer, int index, int indent) throws IOException {
        String pad = " ".repeat(indent);
        out.write(pad + "{ \"name\": \"" + buffer.name + "\", \"uri\": \"" + buffer.path.getFileName().toString() +
            "\", \"byteLength\": " + buffer.length + " }");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class BufferInfo {
        final String name;
        final Path path;
        long length;

        BufferInfo(String name, Path path) {
            this.name = name;
            this.path = path;
            this.length = 0;
        }
    }

    static final class MeshInfo {
        final String materialKey;
        final List<SectionInfo> sections = new ArrayList<>();
        int vertexCount;
        int indexCount;
        int faceCount;
        int faceIndexCount;
        boolean doubleSided;
        String animationName;

        MeshInfo(String materialKey, String animationName) {
            this.materialKey = materialKey;
            this.animationName = animationName;
        }

        void addSection(SectionInfo section, String animationName) {
            sections.add(section);
            vertexCount += section.vertexCount;
            indexCount += section.indexCount;
            faceCount += section.faceCount;
            faceIndexCount += section.faceIndexCount;
            doubleSided |= section.doubleSided;
            if (animationName != null && this.animationName == null) {
                this.animationName = animationName;
            }
        }
    }

    static final class SectionInfo {
        final int vertexCount;
        final int indexCount;
        final int faceCount;
        final int faceIndexCount;
        final boolean doubleSided;
        final long geoOffset;
        final long idxOffset;
        final long uvOffset;
        final long uv1Offset;
        final long loopOffset;
        final long faceCountOffset;
        final long faceIndexOffset;

        SectionInfo(int vertexCount,
                    int indexCount,
                    int faceCount,
                    int faceIndexCount,
                    boolean doubleSided,
                    long geoOffset,
                    long idxOffset,
                    long uvOffset,
                    long uv1Offset,
                    long loopOffset,
                    long faceCountOffset,
                    long faceIndexOffset) {
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.faceCount = faceCount;
            this.faceIndexCount = faceIndexCount;
            this.doubleSided = doubleSided;
            this.geoOffset = geoOffset;
            this.idxOffset = idxOffset;
            this.uvOffset = uvOffset;
            this.uv1Offset = uv1Offset;
            this.loopOffset = loopOffset;
            this.faceCountOffset = faceCountOffset;
            this.faceIndexOffset = faceIndexOffset;
        }
    }

    static final class SpriteSize {
        final int width;
        final int height;

        SpriteSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class SpriteMeta {
        final int width;
        final int height;
        final String texturePath;
        final AtlasPlacement atlas;
        final String animationBase;

        SpriteMeta(int width, int height, String texturePath, AtlasPlacement atlas, String animationBase) {
            this.width = width;
            this.height = height;
            this.texturePath = texturePath;
            this.atlas = atlas;
            this.animationBase = animationBase;
        }
    }

    private record AtlasPlacement(int page, int x, int y, int w, int h) {}

    private final class MeshBuilder {
        private final String bucketKey;
        private final String materialKey;
        private final FloatList positions = new FloatList();
        private final IntList indices = new IntList();
        private final IntList uvLoop = new IntList();
        private final IntList uv1Loop = new IntList();
        private final FloatList loopNormals = new FloatList();
        private final ByteList loopColors = new ByteList();
        private final IntList faceCounts = new IntList();
        private final IntList faceIndices = new IntList();
        // 焊接：只按位置合并顶点，UV数据per-loop存储不受影响
        private final Map<PositionKey, List<VertexSlot>> positionLookup = new HashMap<>(1024);
        private final Map<QuadKey, Boolean> quadDedup = new HashMap<>(2048);
        private final Map<FaceKey, Boolean> faceDedup = new HashMap<>(2048); // Face index deduplication
        private boolean doubleSided;
        private String animationName;

        MeshBuilder(String bucketKey, String materialKey, String animationName) {
            this.bucketKey = bucketKey;
            this.materialKey = materialKey;
            this.animationName = animationName;
        }

        void recordQuad(String spriteKey,
                        String overlaySpriteKey,
                        float[] pos,
                        float[] uv0,
                        float[] uv1,
                        float[] normal,
                        float[] colors,
                        boolean doubleSided,
                        SpriteIdResolver spriteResolver,
                        SpriteSizeResolver sizeResolver) {
            int spriteId = spriteResolver.get(spriteKey);
            SpriteSize size = sizeResolver.get(spriteKey);
            int overlaySpriteId = resolveOverlayId(overlaySpriteKey, spriteResolver);
            int spriteId16 = Math.min(65535, spriteId);
            boolean colormapMode = ExportRuntimeConfig.getColorMode() == ExportRuntimeConfig.ColorMode.COLORMAP;
            int overlayId16 = Math.min(65535, overlaySpriteId);
            if (animationName == null && ExportRuntimeConfig.isAnimationEnabled() && ctx.getTextureRepository().hasAnimation(spriteKey)) {
                animationName = animationBaseName(spriteKey);
            }
            // Per-vertex UDIM page IDs (colormap) or overlay sprite ID (non-colormap)
            int[] uv1PageIds = new int[4];
            float nx = normal != null && normal.length >= 3 ? normal[0] : 0f;
            float ny = normal != null && normal.length >= 3 ? normal[1] : 1f;
            float nz = normal != null && normal.length >= 3 ? normal[2] : 0f;
            int qnx = quantizeNormal(nx);
            int qny = quantizeNormal(ny);
            int qnz = quantizeNormal(nz);
            int[] v = new int[4];
            int[] qp = new int[12];
            int[] uv0_u16 = new int[4];
            int[] uv0_v16 = new int[4];
            int[] uv1_u16 = new int[4];
            int[] uv1_v16 = new int[4];
            byte[] colorBytes = new byte[16]; // 4 verts * 4 channels
            boolean animatedSprite = animationName != null;

            for (int i = 0; i < 4; i++) {
                float px = pos[i * 3];
                float py = pos[i * 3 + 1];
                float pz = pos[i * 3 + 2];
                int qx = quantizePos(px);
                int qy = quantizePos(py);
                int qz = quantizePos(pz);
                qp[i * 3] = qx;
                qp[i * 3 + 1] = qy;
                qp[i * 3 + 2] = qz;

                uv0_u16[i] = quantizeUv(uv0[i * 2], size.width);
                float v0 = uv0[i * 2 + 1];
                if (animatedSprite) {
                    v0 = 1.0f - v0; // Animated textures are exported unflipped; flip V to keep orientation consistent
                }
                uv0_v16[i] = quantizeUv(v0, size.height);

                if (uv1 != null && uv1.length >= 8) {
                    float uv1_u = uv1[i * 2];
                    float uv1_v = uv1[i * 2 + 1];
                    if (colormapMode) {
                        // Per-vertex UDIM extraction
                        int vertexTileU = (int) Math.floor(uv1_u);
                        // ColorMapManager使用负值tileV，这里取ceil(-v)得到整页索引
                        int vertexTileV = (int) Math.ceil(-uv1_v);

                        // 计算小数部分（使用当前顶点的页码）
                        float uFrac = uv1_u - vertexTileU;
                        float vFrac = uv1_v + vertexTileV;

                        // 验证小数部分（调试用，应该在[0,1]范围内）
                        if (uFrac < -0.001f || uFrac > 1.001f || vFrac < -0.001f || vFrac > 1.001f) {
                            ExportLogger.log(String.format(
                                "[VXB] Warning: UV1 fractional out of range at vertex %d: " +
                                "uFrac=%.4f, vFrac=%.4f, raw=(%.4f,%.4f), tile=(%d,%d)",
                                i, uFrac, vFrac, uv1_u, uv1_v, vertexTileU, vertexTileV));
                        }

                        uv1_u16[i] = quantizeUvNormalized(uFrac);
                        uv1_v16[i] = quantizeUvNormalized(vFrac);
                        uv1PageIds[i] = packUdimTile(vertexTileU, vertexTileV);
                    } else {
                        // UV1 for overlay/lightmap: convert UDIM to normalized [0,1] and flip V
                        float uNorm = uv1_u - (float)Math.floor(uv1_u);
                        float vNorm = uv1_v - (float)Math.floor(uv1_v);
                        uv1_u16[i] = quantizeUvNormalized(uNorm);
                        uv1_v16[i] = quantizeUvNormalized(vNorm);
                        uv1PageIds[i] = overlayId16;  // 使用overlay sprite ID
                    }
                } else {
                    uv1_u16[i] = 0;
                    uv1_v16[i] = 0;
                    uv1PageIds[i] = colormapMode ? 0 : overlayId16;
                }

                // 焊接：只基于位置（UV数据per-loop存储，不受焊接影响）
                PositionKey key = new PositionKey(qx, qy, qz);
                v[i] = lookupOrInsertVertex(key, px, py, pz);

                int colorBase = i * 4;
                colorBytes[colorBase] = (byte) colorToByte(colors != null ? colors[colorBase] : 1f);
                colorBytes[colorBase + 1] = (byte) colorToByte(colors != null ? colors[colorBase + 1] : 1f);
                colorBytes[colorBase + 2] = (byte) colorToByte(colors != null ? colors[colorBase + 2] : 1f);
                colorBytes[colorBase + 3] = (byte) colorToByte(colors != null ? colors[colorBase + 3] : 1f);
            }

            // Colormap模式下不使用overlayId16进行去重（每个顶点有独立的页码）
            int dedupOverlayId = colormapMode ? 0 : overlayId16;
            QuadKey qk = new QuadKey(spriteId16, dedupOverlayId, qp, qnx, qny, qnz);
            if (quadDedup.containsKey(qk)) {
                return; // Skip duplicate quad occupying the same space with same material/normal
            }
            quadDedup.put(qk, Boolean.TRUE);

            this.doubleSided |= doubleSided;

            // Face deduplication - skip if same vertex indices already exist
            FaceKey faceKey = FaceKey.of(v[0], v[1], v[2], v[3]);
            if (faceDedup.containsKey(faceKey)) {
                return; // Skip duplicate face before writing any loop data
            }
            faceDedup.put(faceKey, Boolean.TRUE);

            // Quad loop data按顺序写4个角
            for (int i = 0; i < 4; i++) {
                uvLoop.add(uv0_u16[i]);
                uvLoop.add(uv0_v16[i]);
                uvLoop.add(spriteId16);
                uvLoop.add(0);

                uv1Loop.add(uv1_u16[i]);
                uv1Loop.add(uv1_v16[i]);
                uv1Loop.add(uv1PageIds[i]);  // Per-vertex UDIM页码或overlay sprite ID
                uv1Loop.add(0);

                loopNormals.add(nx);
                loopNormals.add(ny);
                loopNormals.add(nz);

                int colorBase = i * 4;
                loopColors.add(colorBytes[colorBase]);
                loopColors.add(colorBytes[colorBase + 1]);
                loopColors.add(colorBytes[colorBase + 2]);
                loopColors.add(colorBytes[colorBase + 3]);
            }

            faceCounts.add(4);
            faceIndices.add(v[0]);
            faceIndices.add(v[1]);
            faceIndices.add(v[2]);
            faceIndices.add(v[3]);
        }

        SectionInfo write(BinaryWriter geoOut,
                       BinaryWriter uvOut,
                       BinaryWriter uv1Out,
                       BinaryWriter loopOut,
                       BinaryWriter idxOut,
                       BinaryWriter faceCountOut,
                       BinaryWriter faceIndexOut,
                       BufferInfo geo,
                       BufferInfo uv,
                       BufferInfo uv1,
                       BufferInfo loop,
                       BufferInfo idx,
                       BufferInfo faceCountBuf,
                       BufferInfo faceIndexBuf) throws IOException {
            long geoOffset = alignAndGetOffset(geoOut, geo);
            for (int i = 0; i < positions.size(); i++) {
                geoOut.writeFloat(positions.get(i));
            }
            geo.length = geoOut.getWritten();

            long idxOffset = alignAndGetOffset(idxOut, idx);
            for (int i = 0; i < indices.size(); i++) {
                idxOut.writeInt(indices.get(i));
            }
            idx.length = idxOut.getWritten();

            long uvOffset = alignAndGetOffset(uvOut, uv);
            int loopCount = uvLoop.size() / 4;
            for (int i = 0; i < uvLoop.size(); i++) {
                uvOut.writeShort(uvLoop.get(i));
            }
            uv.length = uvOut.getWritten();

            long uv1Offset = alignAndGetOffset(uv1Out, uv1);
            for (int i = 0; i < uv1Loop.size(); i++) {
                uv1Out.writeShort(uv1Loop.get(i));
            }
            uv1.length = uv1Out.getWritten();

            long loopOffset = alignAndGetOffset(loopOut, loop);
            for (int i = 0; i < loopCount; i++) {
                int normalBase = i * 3;
                loopOut.writeFloat(loopNormals.get(normalBase));
                loopOut.writeFloat(loopNormals.get(normalBase + 1));
                loopOut.writeFloat(loopNormals.get(normalBase + 2));
                int colorBase = i * 4;
                byte[] colors = loopColors.getArrayDirect();
                loopOut.writeBytes(colors, colorBase, 4);
            }
            loop.length = loopOut.getWritten();

            long faceCountOffset = alignAndGetOffset(faceCountOut, faceCountBuf);
            for (int i = 0; i < faceCounts.size(); i++) {
                faceCountOut.writeShort(faceCounts.get(i));
            }
            faceCountBuf.length = faceCountOut.getWritten();

            long faceIndexOffset = alignAndGetOffset(faceIndexOut, faceIndexBuf);
            for (int i = 0; i < faceIndices.size(); i++) {
                faceIndexOut.writeInt(faceIndices.get(i));
            }
            faceIndexBuf.length = faceIndexOut.getWritten();

            return new SectionInfo(positions.size() / 3, indices.size(), faceCounts.size(),
                faceIndices.size(), doubleSided, geoOffset, idxOffset, uvOffset, uv1Offset, loopOffset,
                faceCountOffset, faceIndexOffset);
        }

        private int packUdimTile(int tileU, int tileV) {
            // UDIM: tileU(0-9) + tileV*10, å…¨éƒ¨ç®—å–æ­£å€¼ï¼Œé˜²æ­¢è¶…å‡º u16
            int page = tileU + tileV * 10;
            if (page < 0) {
                ExportLogger.log(String.format("[VXB] Warning: Negative UDIM page (%d,%d) clamped to 0", tileU, tileV));
                return 0;
            }
            if (page > 65535) {
                ExportLogger.log(String.format("[VXB] Warning: UDIM page overflow (%d,%d)=%d clamped to 65535", tileU, tileV, page));
                return 65535;
            }
            return page;
        }

        private long alignAndGetOffset(BinaryWriter writer, BufferInfo buffer) throws IOException {
            long offset = writer.getWritten();
            int padding = (int) (ALIGNMENT - (offset % ALIGNMENT));
            if (padding != ALIGNMENT) {
                writer.writePadding(padding);
                offset += padding;
            }
            return offset;
        }

        private int quantizeUv(float uv, int size) {
            if (size <= 0) return 0;
            int value = Math.round(uv * size);
            if (value < 0) return 0;
            if (value > 65535) return 65535;
            return value;
        }

        private int quantizeUvNormalized(float uv) {
            int value = Math.round(uv * 65535f);
            if (value < 0) return 0;
            if (value > 65535) return 65535;
            return value;
        }

        private int colorToByte(float v) {
            int i = Math.round(v * 255f);
            if (i < 0) return 0;
            if (i > 255) return 255;
            return i;
        }

        private int quantizePos(float v) {
            return Math.round(v * 10000f);
        }

        private int quantizeNormal(float n) {
            return Math.round(n * 10000f);
        }

        private int lookupOrInsertVertex(PositionKey key, float px, float py, float pz) {
            // 焊接只基于位置，UV数据per-loop存储不受影响
            List<VertexSlot> slots = positionLookup.get(key);
            if (slots != null && !slots.isEmpty()) {
                // 直接返回该位置的第一个顶点索引
                return slots.get(0).index;
            }

            // 创建新顶点
            int newIndex = positions.size() / 3;
            positions.add(px);
            positions.add(py);
            positions.add(pz);

            if (slots == null) {
                slots = new ArrayList<>();
                positionLookup.put(key, slots);
            }
            // 保存占位符
            slots.add(new VertexSlot(newIndex));
            return newIndex;
        }

        private static final class VertexSlot {
            final int index;

            VertexSlot(int index) {
                this.index = index;
            }
        }

        private int resolveOverlayId(String overlaySpriteKey, SpriteIdResolver spriteResolver) {
            if (overlaySpriteKey == null || "voxelbridge:transparent".equals(overlaySpriteKey)) {
                return NO_SPRITE_ID;
            }
            return spriteResolver.get(overlaySpriteKey);
        }

        private record FaceKey(int a, int b, int c, int d) {
            // Store sorted vertex indices to ignore vertex order without allocations
            static FaceKey of(int v0, int v1, int v2, int v3) {
                int[] arr = {v0, v1, v2, v3};
                Arrays.sort(arr);
                return new FaceKey(arr[0], arr[1], arr[2], arr[3]);
            }

            @Override
            public int hashCode() {
                int h = a;
                h = 31 * h + b;
                h = 31 * h + c;
                h = 31 * h + d;
                return h;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof FaceKey other)) return false;
                return a == other.a && b == other.b && c == other.c && d == other.d;
            }
        }

        private record QuadKey(int spriteId, int overlayId, int[] pos, int nx, int ny, int nz) {
            @Override
            public int hashCode() {
                int h = spriteId;
                h = 31 * h + overlayId;
                h = 31 * h + nx;
                h = 31 * h + ny;
                h = 31 * h + nz;
                for (int v : pos) {
                    h = 31 * h + v;
                }
                return h;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof QuadKey other)) return false;
                if (spriteId != other.spriteId || overlayId != other.overlayId || nx != other.nx || ny != other.ny || nz != other.nz) return false;
                if (pos.length != other.pos.length) return false;
                for (int i = 0; i < pos.length; i++) {
                    if (pos[i] != other.pos[i]) return false;
                }
                return true;
            }
        }
    }

    private interface SpriteIdResolver {
        int get(String spriteKey);
    }

    private interface SpriteSizeResolver {
        SpriteSize get(String spriteKey);
    }

    private record PositionKey(int x, int y, int z) {}

    private static final class ChunkState {
        final Map<String, MeshBuilder> meshes = new LinkedHashMap<>(); // bucketKey -> builder
    }

    private static final class FloatList {
        private float[] data = new float[256];
        private int size;

        void add(float value) {
            ensure(size + 1);
            data[size++] = value;
        }

        int size() {
            return size;
        }

        float get(int idx) {
            return data[idx];
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int newSize = data.length;
            while (newSize < needed) {
                newSize <<= 1;
            }
            float[] next = new float[newSize];
            System.arraycopy(data, 0, next, 0, size);
            data = next;
        }
    }

    private static final class IntList {
        private int[] data = new int[256];
        private int size;

        void add(int value) {
            ensure(size + 1);
            data[size++] = value;
        }

        int size() {
            return size;
        }

        int get(int idx) {
            return data[idx];
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int newSize = data.length;
            while (newSize < needed) {
                newSize <<= 1;
            }
            int[] next = new int[newSize];
            System.arraycopy(data, 0, next, 0, size);
            data = next;
        }
    }

    private static final class ByteList {
        private byte[] data = new byte[256];
        private int size;

        void add(int value) {
            ensure(size + 1);
            data[size++] = (byte) value;
        }

        int size() {
            return size;
        }

        byte[] getArrayDirect() {
            return data;
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int newSize = data.length;
            while (newSize < needed) {
                newSize <<= 1;
            }
            byte[] next = new byte[newSize];
            System.arraycopy(data, 0, next, 0, size);
            data = next;
        }
    }

    private static final class BinaryWriter implements AutoCloseable {
        private final OutputStream out;
        private long written;

        BinaryWriter(OutputStream out) {
            this.out = out;
        }

        long getWritten() {
            return written;
        }

        void writeFloat(float value) throws IOException {
            writeInt(Float.floatToIntBits(value));
        }

        void writeInt(int value) throws IOException {
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 24) & 0xFF);
            written += 4;
        }

        void writeShort(int value) throws IOException {
            out.write(value & 0xFF);
            out.write((value >>> 8) & 0xFF);
            written += 2;
        }

        void writeBytes(byte[] data, int length) throws IOException {
            out.write(data, 0, length);
            written += length;
        }

        void writeBytes(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            written += length;
        }

        void writePadding(int count) throws IOException {
            for (int i = 0; i < count; i++) {
                out.write(0);
            }
            written += count;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static final class PhaseProgress {
        private static final long INTERVAL_NANOS = 200_000_000L; // 0.2s
        private long lastUpdate = 0L;
        private float lastPercent = -1f;

        boolean shouldPush(float percent) {
            long now = System.nanoTime();
            float clamped = Math.max(0f, Math.min(1f, percent));
            boolean enoughDelta = Math.abs(clamped - lastPercent) >= 0.01f;
            boolean enoughTime = now - lastUpdate >= INTERVAL_NANOS;
            if (enoughDelta || enoughTime) {
                lastPercent = clamped;
                lastUpdate = now;
                return true;
            }
            return false;
        }
    }
}
