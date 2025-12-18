package com.voxelbridge.export.scene.vxb;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.texture.TextureLoader;

import gnu.trove.map.hash.TObjectIntHashMap;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<String, MeshInfo> meshInfos = new LinkedHashMap<>();
    private final ThreadLocal<Long> currentChunk = new ThreadLocal<>();
    private final Object writeLock = new Object();
    private final BinaryWriter binOut;
    private final BinaryWriter uvRawOut;
    private final BufferInfo bin;
    private final BufferInfo uv;
    private final Path uvRawPath;

    public VxbSceneBuilder(ExportContext ctx, Path outputDir, String baseName) throws IOException {
        this.ctx = ctx;
        this.outputDir = outputDir;
        this.baseName = baseName;
        Files.createDirectories(outputDir);
        Path binPath = outputDir.resolve(baseName + ".bin");
        this.uvRawPath = outputDir.resolve(baseName + ".uvraw.bin");
        this.bin = new BufferInfo("bin", binPath);
        this.uv = new BufferInfo("uv", outputDir.resolve(baseName + ".uv.bin"));
        this.binOut = new BinaryWriter(Files.newOutputStream(binPath));
        this.uvRawOut = new BinaryWriter(Files.newOutputStream(uvRawPath));
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
        if (materialGroupKey == null || spriteKey == null || positions == null || uv0 == null) {
            return;
        }
        Long chunkKey = currentChunk.get();
        if (chunkKey == null) {
            chunkKey = -1L;
        }
        ChunkState chunkState = getOrCreateChunkState(chunkKey);
        MeshBuilder mesh = chunkState.meshes.computeIfAbsent(materialGroupKey, MeshBuilder::new);
        mesh.recordQuad(spriteKey, overlaySpriteKey, positions, uv0, uv1, normal, colors, doubleSided,
            this::getSpriteId, this::getSpriteSize);
    }

    @Override
    public void onChunkStart(int chunkX, int chunkZ) {
        long key = packChunkKey(chunkX, chunkZ);
        currentChunk.set(key);
        getOrCreateChunkState(key);
    }

    @Override
    public void onChunkEnd(int chunkX, int chunkZ, boolean successful) {
        long key = packChunkKey(chunkX, chunkZ);
        currentChunk.remove();
        ChunkState chunkState;
        synchronized (chunkStates) {
            chunkState = chunkStates.remove(key);
        }
        if (chunkState == null) {
            return;
        }
        if (!successful) {
            return;
        }
        flushChunk(chunkState);
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        flushRemainingChunks();
        closeWriters();

        Path uvPath = uv.path;
        Path jsonPath = outputDir.resolve(baseName + ".vxb");
        List<MeshInfo> meshList = new ArrayList<>(meshInfos.values());

        VxbUvRemapper.remapUv(ctx, spriteKeys, spriteSizes, meshList, uvRawPath, uvPath);
        Files.deleteIfExists(uvRawPath);
        writeJson(jsonPath, request, bin, uv, meshList);
        return jsonPath;
    }

    private void flushRemainingChunks() throws IOException {
        List<ChunkState> remaining;
        synchronized (chunkStates) {
            remaining = new ArrayList<>(chunkStates.values());
            chunkStates.clear();
        }
        for (ChunkState state : remaining) {
            flushChunk(state);
        }
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
                MeshInfo info = meshInfos.computeIfAbsent(mesh.materialKey, MeshInfo::new);
                info.addSection(section);
            }
        }
    }

    private long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
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

    private void writeJson(Path jsonPath,
                           SceneWriteRequest request,
                           BufferInfo bin,
                           BufferInfo uv,
                           List<MeshInfo> meshInfos) throws IOException {
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
                out.write("    }" + (i + 1 < spriteKeys.size() ? "," : "") + "\n");
            }
            out.write("  ],\n");

            out.write("  \"meshes\": [\n");
            for (int i = 0; i < meshInfos.size(); i++) {
                MeshInfo m = meshInfos.get(i);
                out.write("    {\n");
                out.write("      \"name\": \"" + escape(m.materialKey) + "\",\n");
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
            out.write("    {\"id\": " + i + ", \"name\": \"" + escape(m.materialKey) + "\", \"mesh\": " + i + "}");
            out.write(i + 1 < meshInfos.size() ? ",\n" : "\n");
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
        return new SpriteMeta(size.width, size.height, texturePath, placement);
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

        MeshInfo(String materialKey) {
            this.materialKey = materialKey;
        }

        void addSection(SectionInfo section) {
            sections.add(section);
            vertexCount += section.vertexCount;
            indexCount += section.indexCount;
            faceCount += section.faceCount;
            faceIndexCount += section.faceIndexCount;
            doubleSided |= section.doubleSided;
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

        SpriteMeta(int width, int height, String texturePath, AtlasPlacement atlas) {
            this.width = width;
            this.height = height;
            this.texturePath = texturePath;
            this.atlas = atlas;
        }
    }

    private record AtlasPlacement(int page, int x, int y, int w, int h) {}

    private static final class MeshBuilder {
        private final String materialKey;
        private final FloatList positions = new FloatList();
        private final IntList indices = new IntList();
        private final IntList uvLoop = new IntList();
        private final IntList uv1Loop = new IntList();
        private final FloatList loopNormals = new FloatList();
        private final ByteList loopColors = new ByteList();
        private final IntList faceCounts = new IntList();
        private final IntList faceIndices = new IntList();
        private final TObjectIntHashMap<PositionKey> positionLookup = new TObjectIntHashMap<>(1024, 0.5f, -1);
        private boolean doubleSided;

        MeshBuilder(String materialKey) {
            this.materialKey = materialKey;
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
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) {
                float px = pos[i * 3];
                float py = pos[i * 3 + 1];
                float pz = pos[i * 3 + 2];
                PositionKey key = new PositionKey(quantize(px), quantize(py), quantize(pz));
                int existing = positionLookup.get(key);
                if (existing >= 0) {
                    v[i] = existing;
                } else {
                    int newIndex = positions.size() / 3;
                    positionLookup.put(key, newIndex);
                    positions.add(px);
                    positions.add(py);
                    positions.add(pz);
                    v[i] = newIndex;
                }
            }

            float nx = normal != null && normal.length >= 3 ? normal[0] : 0f;
            float ny = normal != null && normal.length >= 3 ? normal[1] : 1f;
            float nz = normal != null && normal.length >= 3 ? normal[2] : 0f;
            int spriteId16 = spriteId > 65535 ? 65535 : spriteId;
            int overlayId16 = overlaySpriteId > 65535 ? 65535 : overlaySpriteId;
            this.doubleSided |= doubleSided;

            // Triangle indices for render
            indices.add(v[0]);
            indices.add(v[1]);
            indices.add(v[2]);
            indices.add(v[0]);
            indices.add(v[2]);
            indices.add(v[3]);

            // Loop data follows face corner order (quad) for UV/normal/color
            for (int vi = 0; vi < 4; vi++) {
                int u16 = quantizeUv(uv0[vi * 2], size.width);
                int v16 = quantizeUv(uv0[vi * 2 + 1], size.height);
                uvLoop.add(u16);
                uvLoop.add(v16);
                uvLoop.add(spriteId16);
                uvLoop.add(0);

                int u1 = 0;
                int v1 = 0;
                if (uv1 != null && uv1.length >= 8) {
                    u1 = quantizeUvNormalized(uv1[vi * 2]);
                    v1 = quantizeUvNormalized(uv1[vi * 2 + 1]);
                }
                uv1Loop.add(u1);
                uv1Loop.add(v1);
                uv1Loop.add(overlayId16);
                uv1Loop.add(0);

                loopNormals.add(nx);
                loopNormals.add(ny);
                loopNormals.add(nz);

                int colorBase = vi * 4;
                loopColors.add(colorToByte(colors != null ? colors[colorBase] : 1f));
                loopColors.add(colorToByte(colors != null ? colors[colorBase + 1] : 1f));
                loopColors.add(colorToByte(colors != null ? colors[colorBase + 2] : 1f));
                loopColors.add(colorToByte(colors != null ? colors[colorBase + 3] : 1f));
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
                       BufferInfo faceCount,
                       BufferInfo faceIndex) throws IOException {
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
            int loopCount = faceIndices.size();
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

            long faceCountOffset = alignAndGetOffset(faceCountOut, faceCount);
            for (int i = 0; i < faceCounts.size(); i++) {
                faceCountOut.writeShort(faceCounts.get(i));
            }
            faceCount.length = faceCountOut.getWritten();

            long faceIndexOffset = alignAndGetOffset(faceIndexOut, faceIndex);
            for (int i = 0; i < faceIndices.size(); i++) {
                faceIndexOut.writeInt(faceIndices.get(i));
            }
            faceIndex.length = faceIndexOut.getWritten();

            return new SectionInfo(positions.size() / 3, indices.size(), faceCounts.size(),
                faceIndices.size(), doubleSided, geoOffset, idxOffset, uvOffset, uv1Offset, loopOffset,
                faceCountOffset, faceIndexOffset);
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

        private int quantize(float v) {
            return Math.round(v * 10000f);
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

        private int resolveOverlayId(String overlaySpriteKey, SpriteIdResolver spriteResolver) {
            if (overlaySpriteKey == null || "voxelbridge:transparent".equals(overlaySpriteKey)) {
                return NO_SPRITE_ID;
            }
            return spriteResolver.get(overlaySpriteKey);
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
        final Map<String, MeshBuilder> meshes = new LinkedHashMap<>();
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
}
