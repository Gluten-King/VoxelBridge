package com.voxelbridge.export.scene.gltf;

import de.javagl.jgltf.impl.v2.Buffer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps BinaryChunk and automatically rolls over to new glTF buffers/files
 * when approaching the 2GB per-buffer limit.
 */
final class MultiBinaryChunk implements Closeable {
    // Keep headroom below Integer.MAX_VALUE to avoid overflow on alignment
    private static final long MAX_BUFFER_BYTES = 2_000_000_000L;

    record Slice(int bufferIndex, int byteOffset) {}

    private final Path basePath;
    private final String baseFileName;
    private final de.javagl.jgltf.impl.v2.GlTF gltf;
    private final List<BinaryChunk> chunks = new ArrayList<>();
    private final List<Integer> bufferIndices = new ArrayList<>();
    private final List<Buffer> buffers = new ArrayList<>();
    private boolean closed = false;

    MultiBinaryChunk(Path basePath, de.javagl.jgltf.impl.v2.GlTF gltf) throws IOException {
        this.basePath = basePath;
        this.baseFileName = basePath.getFileName().toString();
        this.gltf = gltf;
        openNewChunk(0);
    }

    synchronized Slice writeFloatArray(float[] values, int length) throws IOException {
        long bytesNeeded = (long) length * 4;
        ensureSpace(4, bytesNeeded);
        int offset = currentChunk().writeFloatArray(values, length);
        return new Slice(currentBufferIndex(), offset);
    }

    synchronized Slice writeIntArray(int[] values, int length) throws IOException {
        long bytesNeeded = (long) length * 4;
        ensureSpace(4, bytesNeeded);
        int offset = currentChunk().writeIntArray(values, length);
        return new Slice(currentBufferIndex(), offset);
    }

    long totalSize() {
        long total = 0;
        for (BinaryChunk chunk : chunks) {
            total += chunk.size();
        }
        return total;
    }

    List<Path> getAllPaths() {
        List<Path> paths = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            paths.add(pathForIndex(i));
        }
        return paths;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        for (BinaryChunk chunk : chunks) {
            chunk.close();
        }
        for (int i = 0; i < buffers.size(); i++) {
            buffers.get(i).setByteLength((int) chunks.get(i).size());
        }
    }

    private void ensureSpace(int alignment, long bytesNeeded) throws IOException {
        BinaryChunk chunk = currentChunk();
        long size = chunk.size();
        long padding = (alignment - (size % alignment)) % alignment;
        if (size + padding + bytesNeeded > MAX_BUFFER_BYTES) {
            openNewChunk(chunks.size());
        }
    }

    private void openNewChunk(int index) throws IOException {
        Path path = pathForIndex(index);
        Buffer buffer = new Buffer();
        buffer.setUri(path.getFileName().toString());
        int globalIndex = (gltf.getBuffers() != null) ? gltf.getBuffers().size() : 0;
        gltf.addBuffers(buffer);
        buffers.add(buffer);
        bufferIndices.add(globalIndex);
        chunks.add(new BinaryChunk(path));
    }

    private Path pathForIndex(int index) {
        if (index == 0) return basePath;
        return basePath.resolveSibling(baseFileName + "." + index);
    }

    private int currentBufferIndex() {
        return bufferIndices.get(bufferIndices.size() - 1);
    }

    private BinaryChunk currentChunk() {
        return chunks.get(chunks.size() - 1);
    }
}
