package com.voxelbridge.voxy.importer;

import com.mojang.serialization.Codec;
import com.voxelbridge.voxy.common.Logger;
import com.voxelbridge.voxy.common.thread.Service;
import com.voxelbridge.voxy.common.thread.ServiceManager;
import com.voxelbridge.voxy.common.util.MemoryBuffer;
import com.voxelbridge.voxy.common.util.Pair;
import com.voxelbridge.voxy.common.util.UnsafeUtil;
import com.voxelbridge.voxy.common.voxelization.VoxelizedSection;
import com.voxelbridge.voxy.common.voxelization.WorldConversionFactory;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.common.world.WorldUpdater;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public class WorldImporter implements IDataImporter {
    private final WorldEngine world;
    private final PalettedContainer<Holder<Biome>> defaultBiomeProvider;
    private final Codec<PalettedContainer<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;
    private final AtomicInteger estimatedTotalChunks = new AtomicInteger();//Slowly converges to the true value
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger chunksProcessed = new AtomicInteger();

    private final ConcurrentLinkedDeque<Runnable> jobQueue = new ConcurrentLinkedDeque<>();
    private final Service service;
    private final ServiceManager serviceManager;
    private final int workerThreads;

    private volatile boolean isRunning;
    private final AtomicBoolean refAcquired = new AtomicBoolean(false);
    private boolean hasChunkFilter = false;
    private int minChunkXFilter;
    private int maxChunkXFilter;
    private int minChunkZFilter;
    private int maxChunkZFilter;
    private boolean hasSectionFilter = false;
    private int minSectionYFilter;
    private int maxSectionYFilter;

    public WorldImporter(WorldEngine worldEngine, Level mcWorld, ServiceManager sm, BooleanSupplier runChecker) {
        this.world = worldEngine;
        this.serviceManager = sm;
        this.workerThreads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 1, 8));
        this.service = sm.createService(()->new Pair<>(()->this.jobQueue.poll().run(), ()->{}), 3, "World importer", runChecker);

        var biomeRegistry = mcWorld.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        this.defaultBiomeProvider = new PalettedContainer<>(biomeRegistry.asHolderIdMap(), defaultBiome, PalettedContainer.Strategy.SECTION_BIOMES);

        this.biomeCodec = PalettedContainer.codecRW(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, defaultBiome);
        this.blockStateCodec = PalettedContainer.codecRW(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }


    @Override
    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning) {
            throw new IllegalStateException();
        }
        if (this.worker == null) {//Can happen if no files
            completionCallback.onCompletion(0);
            return;
        }
        this.isRunning = true;
        this.world.acquireRef();
        this.refAcquired.set(true);
        this.updateCallback = updateCallback;
        this.completionCallback = completionCallback;
        startWorkers();
        this.worker.start();
    }

    @Override
    public WorldEngine getEngine() {
        return this.world;
    }

    private final AtomicBoolean isShutdown = new AtomicBoolean();
    public void shutdown() {
        if (this.isShutdown.getAndSet(true)) {
            return;
        }
        this.isRunning = false;
        if (this.worker != null) {
            try {
                this.worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (this.service.isLive() && this.refAcquired.compareAndSet(true, false)) {
            this.world.releaseRef();
            this.service.shutdown();
        }
        stopWorkers();
        //Free all the remaining entries by running the lambda
        while (!this.jobQueue.isEmpty()) {
            this.jobQueue.poll().run();
        }
    }

    private interface IImporterMethod <T> {
        void importRegion(T file) throws Exception;
    }

    private static final class RegionStats {
        int chunksQueued;
        int chunksEmpty;
        int chunksFiltered;
        int chunksInvalid;
        int chunksExternal;
    }

    private volatile Thread worker;
    private Thread[] workers;
    private IUpdateCallback updateCallback;
    private ICompletionCallback completionCallback;

    private void startWorkers() {
        if (this.workers != null) return;
        this.workers = new Thread[this.workerThreads];
        for (int i = 0; i < this.workerThreads; i++) {
            int idx = i;
            this.workers[i] = new Thread(() -> {
                Thread.currentThread().setName("WorldImporter-Worker-" + idx);
                while (this.isRunning) {
                    int res = this.serviceManager.tryRunAJob();
                    if (res != 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ignored) {}
                    }
                }
            });
            this.workers[i].setDaemon(true);
            this.workers[i].start();
        }
    }

    private void stopWorkers() {
        if (this.workers == null) return;
        for (Thread t : this.workers) {
            if (t != null) t.interrupt();
        }
        this.workers = null;
    }
    public void importRegionDirectoryAsync(File directory) {
        hasChunkFilter = false;
        var files = directory.listFiles((dir, name) -> {
            var sections = name.split("\\.");
            if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                Logger.error("Unknown file: " + name);
                return false;
            }
            return true;
        });
        if (files == null) {
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] import scan failed: directory not found or empty: " + directory);
            return;
        }
        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import scan: regionDir=" + directory + ", files=" + files.length);
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void importRegionDirectoryAsync(File directory, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        hasChunkFilter = true;
        minChunkXFilter = Math.min(minChunkX, maxChunkX);
        maxChunkXFilter = Math.max(minChunkX, maxChunkX);
        minChunkZFilter = Math.min(minChunkZ, maxChunkZ);
        maxChunkZFilter = Math.max(minChunkZ, maxChunkZ);
        var files = directory.listFiles((dir, name) -> {
            var sections = name.split("\\.");
            if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                Logger.error("Unknown file: " + name);
                return false;
            }
            try {
                int rx = Integer.parseInt(sections[1]);
                int rz = Integer.parseInt(sections[2]);
                return regionIntersectsFilter(rx, rz);
            } catch (NumberFormatException e) {
                Logger.error("Invalid format for region position, x: \"" + sections[1] + "\" z: \"" + sections[2] + "\" skipping region");
                return false;
            }
        });
        if (files == null) {
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] import scan failed: directory not found or empty: " + directory);
            return;
        }
        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] import scan: regionDir=%s files=%d filterChunks=[%d..%d, %d..%d]",
            directory, files.length, minChunkXFilter, maxChunkXFilter, minChunkZFilter, maxChunkZFilter));
        Arrays.sort(files, File::compareTo);
        this.importRegionsAsync(files, this::importRegionFile);
    }

    public void setSectionFilter(int minY, int maxY) {
        hasSectionFilter = true;
        int minSec = Math.min(minY, maxY) >> 4;
        int maxSec = Math.max(minY, maxY) >> 4;
        minSectionYFilter = minSec;
        maxSectionYFilter = maxSec;
    }

    public void importZippedRegionDirectoryAsync(File zip, String innerDirectory) {
        try {
            innerDirectory = innerDirectory.replace("\\\\", "\\\\").replace("\\", "/");
            var file = ZipFile.builder().setFile(zip).get();
            ArrayList<ZipArchiveEntry> regions = new ArrayList<>();
            for (var e = file.getEntries(); e.hasMoreElements();) {
                var entry = e.nextElement();
                if (entry.isDirectory()||!entry.getName().startsWith(innerDirectory)) {
                    continue;
                }
                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");
                if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
                    Logger.error("Unknown file: " + name);
                    continue;
                }
                regions.add(entry);
            }
            this.importRegionsAsync(regions.toArray(ZipArchiveEntry[]::new), (entry)->{
                if (entry.getSize() == 0) {
                    return;
                }
                var buf = new MemoryBuffer(entry.getSize());
                try (var channel = Channels.newChannel(file.getInputStream(entry))) {
                    if (channel.read(buf.asByteBuffer()) != buf.size) {
                        buf.free();
                        throw new IllegalStateException("Could not read full zip entry");
                    }
                }

                var parts = entry.getName().split("/");
                var name = parts[parts.length-1];
                var sections = name.split("\\.");

                try {
                    int rx = Integer.parseInt(sections[1]);
                    int rz = Integer.parseInt(sections[2]);
                    VoxelBridgeLogger.info(LogModule.LOD, String.format(
                        "[LOD] region zip start r.%d.%d entry=%s size=%d",
                        rx, rz, entry.getName(), entry.getSize()));
                    RegionStats stats = this.importRegion(buf, rx, rz);
                    VoxelBridgeLogger.info(LogModule.LOD, String.format(
                        "[LOD] region zip done r.%d.%d queued=%d empty=%d filtered=%d invalid=%d external=%d",
                        rx, rz, stats.chunksQueued, stats.chunksEmpty, stats.chunksFiltered,
                        stats.chunksInvalid, stats.chunksExternal));
                } catch (NumberFormatException e) {
                    Logger.error("Invalid format for region position, x: \""+sections[1]+"\" z: \"" + sections[2] + "\" skipping region");
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private <T> void importRegionsAsync(T[] regionFiles, IImporterMethod<T> importer) {
        this.totalChunks.set(0);
        this.estimatedTotalChunks.set(0);
        this.chunksProcessed.set(0);
        this.worker = new Thread(() -> {
            VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import worker started, regions=" + regionFiles.length);
            this.estimatedTotalChunks.addAndGet(regionFiles.length*1024);
            for (var file : regionFiles) {
                this.estimatedTotalChunks.addAndGet(-1024);
                try {
                    importer.importRegion(file);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                while ((this.totalChunks.get()-this.chunksProcessed.get() > 10_000) && this.isRunning) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (!this.isRunning) {
                    this.service.blockTillEmpty();
                    this.completionCallback.onCompletion(this.totalChunks.get());
                    this.isRunning = false;
                    this.worker = null;
                    return;
                }
            }
            this.service.blockTillEmpty();
            while (this.chunksProcessed.get() != this.totalChunks.get() && this.isRunning) {
                Thread.yield();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (!this.isShutdown.getAndSet(true)) {
                this.worker = null;
                this.service.shutdown();
                if (this.refAcquired.compareAndSet(true, false)) {
                    this.world.releaseRef();
                }
            }
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] import worker done, totalChunks=%d processed=%d",
                this.totalChunks.get(), this.chunksProcessed.get()));
            this.completionCallback.onCompletion(this.totalChunks.get());
            this.isRunning = false;
        });
        this.worker.setName("World importer");
    }

    public boolean isBusy() {
        return this.isRunning || this.worker != null;
    }

    public boolean isRunning() {
        return this.isRunning || (this.worker != null && this.worker.isAlive());
    }

    private void importRegionFile(File file) throws IOException {
        var name = file.getName();
        var sections = name.split("\\.");
        if (sections.length != 4 || (!sections[0].equals("r")) || (!sections[3].equals("mca"))) {
            Logger.error("Unknown file: " + name);
            throw new IllegalStateException();
        }
        int rx = 0;
        int rz = 0;
        try {
            rx = Integer.parseInt(sections[1]);
            rz = Integer.parseInt(sections[2]);
        } catch (NumberFormatException e) {
            Logger.error("Invalid format for region position, x: "+sections[1]+" z: " + sections[2] + " skipping region");
            return;
        }
        if (hasChunkFilter && !regionIntersectsFilter(rx, rz)) {
            return;
        }
        try (var fileStream = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            if (fileStream.size() == 0) {
                return;
            }
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] region file start r.%d.%d size=%d bytes path=%s",
                rx, rz, fileStream.size(), file));
            var fileData = new MemoryBuffer(fileStream.size());
            if (fileStream.read(fileData.asByteBuffer(), 0) < 8192) {
                fileData.free();
                Logger.warn("Header of region file invalid");
                return;
            }
            RegionStats stats = this.importRegion(fileData, rx, rz);
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] region file done r.%d.%d queued=%d empty=%d filtered=%d invalid=%d external=%d",
                rx, rz, stats.chunksQueued, stats.chunksEmpty, stats.chunksFiltered,
                stats.chunksInvalid, stats.chunksExternal));
        }
    }


    private RegionStats importRegion(MemoryBuffer regionFile, int x, int z) {
        RegionStats stats = new RegionStats();
        java.util.concurrent.atomic.AtomicInteger regionPending = new java.util.concurrent.atomic.AtomicInteger(0);
        //Find and load all saved chunks
        if (regionFile.size < 8192) {//File not big enough
            Logger.warn("Header of region file invalid");
            stats.chunksInvalid++;
            regionFile.free();
            return stats;
        }
        for (int idx = 0; idx < 1024; idx++) {
            if (hasChunkFilter) {
                int cx = (x << 5) + (idx & 31);
                int cz = (z << 5) + ((idx >> 5) & 31);
                if (!chunkInFilter(cx, cz)) {
                    stats.chunksFiltered++;
                    continue;
                }
            }
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(regionFile.address+idx*4));//Assumes little endian
            if (sectorMeta == 0) {
                //Empty chunk
                stats.chunksEmpty++;
                continue;
            }
            int sectorStart = sectorMeta>>>8;
            int sectorCount = sectorMeta&((1<<8)-1);

            if (sectorCount == 0) {
                stats.chunksInvalid++;
                continue;
            }

            //TODO: create memory copy for each section
            if (regionFile.size < ((sectorCount-1) + sectorStart) * 4096L) {
                Logger.warn("Cannot access chunk sector as it goes out of bounds. start bytes: " + (sectorStart*4096) + " sector count: " + sectorCount + " fileSize: " + regionFile.size);
                stats.chunksInvalid++;
                continue;
            }

            {
                long base = regionFile.address + sectorStart * 4096L;
                int chunkLen = sectorCount * 4096;
                int m = Integer.reverseBytes(MemoryUtil.memGetInt(base));
                byte b = MemoryUtil.memGetByte(base + 4L);
                if (m == 0) {
                    Logger.error("Chunk is allocated, but stream is missing");
                    stats.chunksInvalid++;
                } else {
                    int n = m - 1;
                    if (regionFile.size < (n + sectorStart*4096L)) {
                        Logger.warn("Chunk stream to small");
                        stats.chunksInvalid++;
                    } else if ((b & 128) != 0) {
                        if (n != 0) {
                            Logger.error("Chunk has both internal and external streams");
                        }
                        Logger.error("Chunk has external stream which is not supported");
                        stats.chunksExternal++;
                    } else if (n > chunkLen-5) {
                        Logger.error("Chunk stream is truncated: expected "+n+" but read " + (chunkLen-5));
                        stats.chunksInvalid++;
                    } else if (n < 0) {
                        Logger.error("Declared size of chunk is negative");
                        stats.chunksInvalid++;
                    } else {
                        regionPending.incrementAndGet();
                        var data = MemoryBuffer.createUntrackedUnfreeableRawFrom(base + 5, n);
                        this.jobQueue.add(()-> {
                            if (!this.isRunning) {
                                if (regionPending.decrementAndGet() == 0) {
                                    regionFile.free();
                                }
                                return;
                            }
                            try {
                                try (var decompressedData = this.decompress(b, data)) {
                                    if (decompressedData == null) {
                                        Logger.error("Error decompressing chunk data");
                                    } else {
                                        var nbt = NbtIo.read(decompressedData);
                                        this.importChunkNBT(nbt, x, z);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                if (regionPending.decrementAndGet() == 0) {
                                    regionFile.free();
                                }
                            }
                        });
                        this.totalChunks.incrementAndGet();
                        this.estimatedTotalChunks.incrementAndGet();
                        this.service.execute();
                        stats.chunksQueued++;
                    }
                }
            }
        }
        if (regionPending.get() == 0) {
            regionFile.free();
        }
        return stats;
    }

    private static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;
            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (this.offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, this.available());
                if (len == 0) {
                    return -1;
                }
                UnsafeUtil.memcpy(data.address+this.offset, len, b, off); this.offset+=len;
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size-this.offset);
            }
        };
    }

    private DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    private void importChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
        if (!chunk.contains("Status")) {
            //Its not real so decrement the chunk
            this.totalChunks.decrementAndGet();
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] chunk missing Status tag, skipping");
            return;
        }

        //Dont process non full chunk sections
        var status = ChunkStatus.byName(getStringOr(chunk, "Status", null));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {//We also import empty since they are from data upgrade
            this.totalChunks.decrementAndGet();
            VoxelBridgeLogger.info(LogModule.LOD, "[LOD] chunk skipped due to status=" + status);
            return;
        }

        try {
            int x = getIntOr(chunk, "xPos", Integer.MIN_VALUE);
            int z = getIntOr(chunk, "zPos", Integer.MIN_VALUE);
            if (x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, expected: (" + regionX + ", " + regionZ+"), got: " + "(" + (x>>5) + ", " + (z>>5)+"), importing anyway");
            }

            ListTag sections = chunk.contains("sections", Tag.TAG_LIST) ? chunk.getList("sections", Tag.TAG_COMPOUND) : new ListTag();
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] chunk start x=%d z=%d status=%s sections=%d",
                x, z, status, sections.size()));
            for (var sectionE : sections) {
                var section = (CompoundTag) sectionE;
                int y = getSectionY(section);
                if (y == Integer.MIN_VALUE) {
                    VoxelBridgeLogger.warn(LogModule.LOD, String.format(
                        "[LOD] section missing Y tag, skipping x=%d z=%d", x, z));
                    continue;
                }
                if (hasSectionFilter && (y < minSectionYFilter || y > maxSectionYFilter)) {
                    continue;
                }
                this.importSectionNBT(x, y, z, section);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }

        this.updateCallback.onUpdate(this.chunksProcessed.incrementAndGet(), this.estimatedTotalChunks.get());
    }

    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundTag section) {
        if (!section.contains("block_states", Tag.TAG_COMPOUND) || section.getCompound("block_states").isEmpty()) {
            VoxelBridgeLogger.info(LogModule.LOD, String.format(
                "[LOD] section skip (no block_states) x=%d y=%d z=%d", x, y, z));
            return;
        }

        byte[] blockLightData = section.contains("BlockLight", Tag.TAG_BYTE_ARRAY) ? section.getByteArray("BlockLight") : EMPTY;
        byte[] skyLightData = section.contains("SkyLight", Tag.TAG_BYTE_ARRAY) ? section.getByteArray("SkyLight") : EMPTY;

        DataLayer blockLight;
        if (blockLightData.length != 0) {
            blockLight = new DataLayer(blockLightData);
        } else {
            blockLight = null;
        }

        DataLayer skyLight;
        if (skyLightData.length != 0) {
            skyLight = new DataLayer(skyLightData);
        } else {
            skyLight = null;
        }

        var blockStatesRes = blockStateCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states"));
        var blockStatesOpt = blockStatesRes.resultOrPartial(msg ->
            VoxelBridgeLogger.warn(LogModule.LOD, String.format(
                "[LOD] section parse warning x=%d y=%d z=%d msg=%s", x, y, z, msg))
        );
        if (blockStatesOpt.isEmpty()) {
            VoxelBridgeLogger.warn(LogModule.LOD, String.format(
                "[LOD] section parse failed x=%d y=%d z=%d", x, y, z));
            return;
        }
        var blockStates = blockStatesOpt.get();
        var biomes = this.defaultBiomeProvider;
        var optBiomes = section.getCompound("biomes");
        if (!optBiomes.isEmpty()) {
            biomes = this.biomeCodec.parse(NbtOps.INSTANCE, optBiomes).result().orElse(this.defaultBiomeProvider);
        }
        VoxelizedSection csec = WorldConversionFactory.convert(
                SECTION_CACHE.get().setPosition(x, y, z),
                this.world.getMapper(),
                blockStates,
                biomes,
                (bx, by, bz) -> {
                    int block = 0;
                    int sky = 0;
                    if (blockLight != null) {
                        block = blockLight.get(bx, by, bz);
                    }
                    if (skyLight != null) {
                        sky = skyLight.get(bx, by, bz);
                    }
                    return (byte) (sky|(block<<4));
                }
        );

        WorldConversionFactory.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] section stored x=%d y=%d z=%d nonAir=%d",
            x, y, z, csec.lvl0NonAirCount));
    }

    private static int getIntOr(CompoundTag tag, String key, int defaultValue) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : defaultValue;
    }

    private static String getStringOr(CompoundTag tag, String key, String defaultValue) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : defaultValue;
    }

    private static int getSectionY(CompoundTag tag) {
        if (tag.contains("Y", Tag.TAG_INT)) {
            return tag.getInt("Y");
        }
        if (tag.contains("Y", Tag.TAG_BYTE)) {
            return tag.getByte("Y");
        }
        if (tag.contains("Y", Tag.TAG_SHORT)) {
            return tag.getShort("Y");
        }
        if (tag.contains("y", Tag.TAG_BYTE)) {
            return tag.getByte("y");
        }
        if (tag.contains("y", Tag.TAG_INT)) {
            return tag.getInt("y");
        }
        return Integer.MIN_VALUE;
    }

    private boolean regionIntersectsFilter(int regionX, int regionZ) {
        if (!hasChunkFilter) return true;
        int minX = regionX << 5;
        int maxX = minX + 31;
        int minZ = regionZ << 5;
        int maxZ = minZ + 31;
        return !(maxX < minChunkXFilter || minX > maxChunkXFilter || maxZ < minChunkZFilter || minZ > maxChunkZFilter);
    }

    private boolean chunkInFilter(int chunkX, int chunkZ) {
        if (!hasChunkFilter) return true;
        return chunkX >= minChunkXFilter && chunkX <= maxChunkXFilter
            && chunkZ >= minChunkZFilter && chunkZ <= maxChunkZFilter;
    }
}

