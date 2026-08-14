package com.voxelbridge.export.scene.gltf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** Segmented memory-mapped reader for the temporary geometry spool. */
final class BinarySpoolReader implements AutoCloseable {
    private static final long SEGMENT_SIZE = (long) 1024 * 1024 * 1024;

    private final List<MappedByteBuffer> segments = new ArrayList<>();
    private final long fileSize;

    BinarySpoolReader(FileChannel channel) throws IOException {
        fileSize = channel.size();
        long position = 0;
        while (position < fileSize) {
            long size = Math.min(SEGMENT_SIZE, fileSize - position);
            segments.add(channel.map(FileChannel.MapMode.READ_ONLY, position, size));
            position += size;
        }
    }

    void read(long offset, ByteBuffer destination) {
        int remaining = destination.remaining();
        long currentOffset = offset;
        while (remaining > 0) {
            int segmentIndex = (int) (currentOffset / SEGMENT_SIZE);
            long offsetInSegment = currentOffset % SEGMENT_SIZE;
            if (segmentIndex >= segments.size()) {
                throw new IndexOutOfBoundsException("Read beyond spool size: " + currentOffset);
            }

            MappedByteBuffer segment = segments.get(segmentIndex);
            ByteBuffer view = segment.duplicate();
            view.position((int) offsetInSegment);
            int available = view.limit() - view.position();
            int count = Math.min(remaining, available);
            view.limit(view.position() + count);
            destination.put(view);
            currentOffset += count;
            remaining -= count;
        }
    }

    @Override
    public void close() {
        for (MappedByteBuffer buffer : segments) unmap(buffer);
        segments.clear();
    }

    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) return;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            unsafeClass.getMethod("invokeCleaner", ByteBuffer.class).invoke(unsafe, buffer);
        } catch (Exception primaryFailure) {
            try {
                java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                cleanerMethod.setAccessible(true);
                Object cleaner = cleanerMethod.invoke(buffer);
                if (cleaner != null) cleaner.getClass().getMethod("clean").invoke(cleaner);
            } catch (Exception ignored) {
                // Best effort. The temporary file cleanup reports a useful warning if Windows retains the map.
            }
        }
    }
}
