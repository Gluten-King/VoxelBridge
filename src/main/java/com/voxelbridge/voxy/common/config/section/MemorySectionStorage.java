package com.voxelbridge.voxy.common.config.section;

import com.voxelbridge.voxy.common.world.WorldSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.function.LongConsumer;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

public class MemorySectionStorage extends SectionStorage {
    private final ConcurrentHashMap<Long, long[]> sections = new ConcurrentHashMap<>();
    private final Int2ObjectOpenHashMap<byte[]> idMappings = new Int2ObjectOpenHashMap<>();

    @Override
    public int loadSection(WorldSection into) {
        long[] data = this.sections.get(into.key);
        if (data != null) {
            long[] dst = into._unsafeGetRawDataArray();
            System.arraycopy(data, 0, dst, 0, data.length);
            return 0; // Success
        }
        return 1; // Not found/Air
    }

    @Override
    public void saveSection(WorldSection section) {
        this.sections.put(section.key, section.copyData());
    }

    @Override
    public void iterateStoredSectionPositions(LongConsumer consumer) {
        this.sections.keySet().forEach((Long key) -> consumer.accept(key));
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        synchronized (this.idMappings) {
            this.idMappings.put(id, bytes);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        synchronized (this.idMappings) {
            return new Int2ObjectOpenHashMap<>(this.idMappings);
        }
    }

    @Override
    public void flush() {
        // No-op for memory storage
    }

    @Override
    public void close() {
        this.sections.clear();
        this.idMappings.clear();
    }
}

