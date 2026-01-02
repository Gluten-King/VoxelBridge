package com.voxelbridge.export;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.scene.gltf.GltfSceneBuilder;
import com.voxelbridge.export.texture.ColorMapManager;
import com.voxelbridge.export.texture.TextureAtlasManager;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.voxy.common.config.section.MemorySectionStorage;
import com.voxelbridge.voxy.common.thread.ServiceManager;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.voxy.importer.WorldImporter;
import com.voxelbridge.voxy.mesh.VoxelMesher;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal LOD export path: imports .mca directly and meshes with simplified proxy cubes.
 */
public final class LodExportService {

    private LodExportService() {}

    public static Path exportRegion(Level level, BlockPos pos1, BlockPos pos2, Path outDir) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("LOD export currently only supported in singleplayer.");
        }
        ExportContext ctx = new ExportContext(mc);
        ctx.resetConsumedBlocks();
        ctx.clearTextureState();
        ctx.setBlockEntityExportEnabled(false);
        ctx.setCoordinateMode(ExportRuntimeConfig.getCoordinateMode());
        ctx.setVanillaRandomTransformEnabled(ExportRuntimeConfig.isVanillaRandomTransformEnabled());
        ctx.setDiscoveryMode(false);
        TextureAtlasManager.initializeReservedSlots(ctx);
        ColorMapManager.initializeReservedSlots(ctx);

        // Resolve correct dimension path (overworld/nether/end)
        Path regionDir;
        if (level.dimension() == Level.OVERWORLD) {
            regionDir = server.getWorldPath(LevelResource.ROOT).resolve("region");
        } else {
            // dimensions/<namespace>/<path>/region
            regionDir = server.getWorldPath(LevelResource.ROOT)
                    .resolve("dimensions")
                    .resolve(level.dimension().location().getNamespace())
                    .resolve(level.dimension().location().getPath())
                    .resolve("region");
        }
        if (!Files.isDirectory(regionDir)) {
            throw new IllegalStateException("Region directory not found: " + regionDir);
        }
        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] exportRegion start, regionDir=" + regionDir);

        // Calculate LOD thresholds (in blocks, Euclidean distance / radius)
        // LOD 0 covers [center - fineR, center + fineR]
        int fineChunks = ExportRuntimeConfig.getLodFineChunkRadius();
        double lod0Dist = fineChunks * 16.0;
        double lod1Dist = lod0Dist * 2.0;
        double lod2Dist = lod0Dist * 4.0;
        double lod3Dist = lod0Dist * 8.0;

        // Always use selection center
        double cx = (pos1.getX() + pos2.getX()) / 2.0;
        double cy = (pos1.getY() + pos2.getY()) / 2.0;
        double cz = (pos1.getZ() + pos2.getZ()) / 2.0;

        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] center=[%.1f, %.1f, %.1f] fineChunks=%d lod0Dist=%.1f", cx, cy, cz, fineChunks, lod0Dist));

        // Build output request
        String baseName = String.format("lod_region_%d_%d_%d__%d_%d_%d",
                Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
                Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ()));
        Path outputPath = outDir.resolve(baseName + ".gltf");
        SceneWriteRequest request = new SceneWriteRequest(baseName, outDir, outputPath);
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        double offsetX = (ExportRuntimeConfig.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minX + maxX) / 2.0
            : 0;
        double offsetY = (ExportRuntimeConfig.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minY + maxY) / 2.0
            : 0;
        double offsetZ = (ExportRuntimeConfig.getCoordinateMode() == com.voxelbridge.export.CoordinateMode.CENTERED)
            ? -(minZ + maxZ) / 2.0
            : 0;

        try {
            SceneSink sink = new GltfSceneBuilder(ctx, outDir);
            int meshed = appendLodGeometry(level, regionDir, pos1, pos2, sink, ctx, outDir, cx, cy, cz,
                lod0Dist, lod1Dist, lod2Dist, lod3Dist, -1, offsetX, offsetY, offsetZ, true);
            VoxelBridgeLogger.info(LogModule.LOD, "[LOD] exportRegion meshedSections=" + meshed);
            sink.write(request);
        } finally {
            ctx.clearTextureState();
        }

        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] exportRegion done, output=" + outputPath);
        return outputPath;
    }

    public static int appendLodGeometry(Level level,
                                        Path regionDir,
                                        BlockPos pos1,
                                        BlockPos pos2,
                                        SceneSink sink,
                                        ExportContext ctx,
                                        Path outDir,
                                        double cx,
                                        double cy,
                                        double cz,
                                        double lod0Dist,
                                        double lod1Dist,
                                        double lod2Dist,
                                        double lod3Dist,
                                        double skipDist,
                                        double offsetX,
                                        double offsetY,
                                        double offsetZ,
                                        boolean requireGeometry) throws Exception {
        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] append start, regionDir=" + regionDir + ", skipDist=" + skipDist);
        long tImportStart = System.nanoTime();
        try {
            long mcaCount = java.nio.file.Files.list(regionDir)
                .filter(p -> p.getFileName().toString().endsWith(".mca"))
                .count();
            VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import start, mcaFiles=" + mcaCount);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] import start, failed to count region files: " + e.getMessage());
        }
        ServiceManager serviceManager = new ServiceManager(i -> {});
        MemorySectionStorage storage = new MemorySectionStorage();
        WorldEngine engine = new WorldEngine(storage);
        engine.setSaveCallback((eng, section) -> storage.saveSection(section));
        engine.setDirtyCallback((section, flags, neighbors) -> storage.saveSection(section));

        WorldImporter importer = new WorldImporter(engine, level, serviceManager, () -> true);

        CompletableFuture<Void> importFuture = new CompletableFuture<>();
        final long[] lastLogNanos = {0L};
        int selMinX = Math.min(pos1.getX(), pos2.getX());
        int selMaxX = Math.max(pos1.getX(), pos2.getX());
        int selMinY = Math.min(pos1.getY(), pos2.getY());
        int selMaxY = Math.max(pos1.getY(), pos2.getY());
        int selMinZ = Math.min(pos1.getZ(), pos2.getZ());
        int selMaxZ = Math.max(pos1.getZ(), pos2.getZ());

        BlockPos selMin = new BlockPos(selMinX, selMinY, selMinZ);
        BlockPos selMax = new BlockPos(selMaxX, selMaxY, selMaxZ);

        int minChunkX = selMinX >> 4;
        int maxChunkX = selMaxX >> 4;
        int minChunkZ = selMinZ >> 4;
        int maxChunkZ = selMaxZ >> 4;

        // Align import bounds to LOD section size to avoid partial LOD sections at the edges.
        double dxMax = Math.max(Math.abs(cx - selMinX), Math.abs(cx - selMaxX));
        double dzMax = Math.max(Math.abs(cz - selMinZ), Math.abs(cz - selMaxZ));
        double maxDist = Math.hypot(dxMax, dzMax);
        int maxRequiredLvl;
        if (!ExportRuntimeConfig.isLodEnabled()) {
            maxRequiredLvl = 0;
        } else if (maxDist < lod0Dist) {
            maxRequiredLvl = 0;
        } else if (maxDist < lod1Dist) {
            maxRequiredLvl = 1;
        } else if (maxDist < lod2Dist) {
            maxRequiredLvl = 2;
        } else if (maxDist < lod3Dist) {
            maxRequiredLvl = 3;
        } else {
            maxRequiredLvl = 4;
        }

        int alignSize = 1 << (maxRequiredLvl + 1); // chunk units per section at this LOD
        int alignedMinChunkX = Math.floorDiv(minChunkX, alignSize) * alignSize;
        int alignedMaxChunkX = Math.floorDiv(maxChunkX, alignSize) * alignSize + (alignSize - 1);
        int alignedMinChunkZ = Math.floorDiv(minChunkZ, alignSize) * alignSize;
        int alignedMaxChunkZ = Math.floorDiv(maxChunkZ, alignSize) * alignSize + (alignSize - 1);

        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] import bounds aligned lvl=%d size=%d chunks=[%d..%d, %d..%d]",
            maxRequiredLvl, alignSize, alignedMinChunkX, alignedMaxChunkX, alignedMinChunkZ, alignedMaxChunkZ));

        importer.importRegionDirectoryAsync(regionDir.toFile(), alignedMinChunkX, alignedMaxChunkX, alignedMinChunkZ, alignedMaxChunkZ);
        importer.runImport(
            (finished, total) -> {
                long now = System.nanoTime();
                if (finished == 0 || finished % 1024 == 0 || now - lastLogNanos[0] > 5_000_000_000L) {
                    VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import progress " + finished + "/" + total);
                    lastLogNanos[0] = now;
                }
            },
            total -> importFuture.complete(null)
        );

        int workerCount = Math.max(1, ExportRuntimeConfig.getExportThreadCount());
        java.util.List<Thread> jobRunners = new java.util.ArrayList<>(workerCount);
        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import job runners started, workers=" + workerCount);
        for (int i = 0; i < workerCount; i++) {
            Thread runner = new Thread(() -> {
                try {
                    while (!importFuture.isDone() && importer.isRunning()) {
                        int result = serviceManager.tryRunAJob();
                        if (result != 0) {
                            Thread.sleep(2);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "VoxelBridge-LOD-Import-" + (i + 1));
            runner.setDaemon(true);
            runner.start();
            jobRunners.add(runner);
        }

        importFuture.join();
        for (Thread runner : jobRunners) {
            try {
                runner.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] import job runners stopped");
        importer.shutdown();
        double importSeconds = (System.nanoTime() - tImportStart) / 1_000_000_000.0;
        VoxelBridgeLogger.info(LogModule.LOD, String.format("[LOD] import done (%.2fs), starting mesh", importSeconds));

        final int[] meshed = {0};
        final int[] visited = {0};
        CountingSceneSink countingSink = new CountingSceneSink(sink);

        java.util.List<Long> sectionIds = new java.util.ArrayList<>();
        java.util.Set<Long> availableSections = new java.util.HashSet<>();
        storage.iterateStoredSectionPositions(sectionId -> {
            sectionIds.add(sectionId);
            availableSections.add(sectionId);
        });

        java.util.List<Long> sectionsToMesh = new java.util.ArrayList<>();
        int sectionsIntersecting = 0;
        int debugLogCount = 0;
        int fallbackMeshed = 0;

        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] storage contains " + sectionIds.size() + " sections");

        for (long sectionId : sectionIds) {
            visited[0]++;
            int lvl = WorldEngine.getLevel(sectionId);
            int x = WorldEngine.getX(sectionId);
            int y = WorldEngine.getY(sectionId);
            int z = WorldEngine.getZ(sectionId);

            // Section center in world space
            int scale = 1 << lvl;
            int size = 32 * scale;
            double wx = (x * size) + (size / 2.0);
            double wy = (y * size) + (size / 2.0);
            double wz = (z * size) + (size / 2.0);

            // Only process sections intersecting the selection AABB
            if (!intersectsAabb(wx - size / 2.0, wy - size / 2.0, wz - size / 2.0, size,
                    selMin, selMax)) {
                continue;
            }
            sectionsIntersecting++;

            double dx = cx - wx;
            double dz = cz - wz;
            // Euclidean distance
            double dist = Math.hypot(dx, dz);
            if (skipDist >= 0 && dist < skipDist) {
                continue;
            }

            int myRequiredLvl = getRequiredLevel(dist, lod0Dist, lod1Dist, lod2Dist, lod3Dist);
            
            if (debugLogCount < 5) {
                VoxelBridgeLogger.info(LogModule.LOD, String.format(
                    "[LOD] check section lvl=%d pos=[%d,%d,%d] wPos=[%.1f,%.1f,%.1f] dist=%.1f reqLvl=%d",
                    lvl, x, y, z, wx, wy, wz, dist, myRequiredLvl));
                debugLogCount++;
            }

            if (lvl == myRequiredLvl) {
                // Check if this section is blocked by any higher-detail child.
                boolean blocked = false;
                if (lvl > 0) {
                    int childLvl = lvl - 1;
                    int cScale = 1 << childLvl;
                    int cSize = 32 * cScale;
                    double childThreshold = getThreshold(childLvl, lod0Dist, lod1Dist, lod2Dist, lod3Dist);

                    // Check 8 children
                    for (int dx2 = 0; dx2 <= 1; dx2++) {
                        for (int dy2 = 0; dy2 <= 1; dy2++) {
                            for (int dz2 = 0; dz2 <= 1; dz2++) {
                                int cx2 = x * 2 + dx2;
                                int cy2 = y * 2 + dy2;
                                int cz2 = z * 2 + dz2;
                                long childId = WorldEngine.getWorldSectionId(childLvl, cx2, cy2, cz2);
                                if (availableSections.contains(childId)) {
                                    double cwx = (cx2 * cSize) + (cSize / 2.0);
                                    double cwz = (cz2 * cSize) + (cSize / 2.0);
                                    double cdx = cx - cwx;
                                    double cdz = cz - cwz;
                                    if (Math.hypot(cdx, cdz) < childThreshold) {
                                        blocked = true;
                                        // Break out of inner loops
                                        dx2 = 2; dy2 = 2; dz2 = 2;
                                    }
                                }
                            }
                        }
                    }
                }

                if (!blocked) {
                    sectionsToMesh.add(sectionId);
                }
            } else if (lvl < myRequiredLvl) {
                // Current section is more detailed than required.
                // Check if Parent effectively covers this area.
                int pLvl = lvl + 1;
                int px = Math.floorDiv(x, 2);
                int py = Math.floorDiv(y, 2);
                int pz = Math.floorDiv(z, 2);
                long parentId = WorldEngine.getWorldSectionId(pLvl, px, py, pz);

                boolean parentEffective = false;
                if (availableSections.contains(parentId)) {
                    int pScale = 1 << pLvl;
                    int pSize = 32 * pScale;
                    double pWx = (px * pSize) + (pSize / 2.0);
                    double pWz = (pz * pSize) + (pSize / 2.0);
                    double pDx = cx - pWx;
                    double pDz = cz - pWz;
                    double pDist = Math.hypot(pDx, pDz);
                    int pRequired = getRequiredLevel(pDist, lod0Dist, lod1Dist, lod2Dist, lod3Dist);

                    if (pRequired > pLvl) {
                        // Parent delegates up. Assume effective coverage from upstream.
                        parentEffective = true;
                    } else if (pRequired < pLvl) {
                        // Parent wants more detail. It will NOT render.
                        parentEffective = false;
                    } else {
                        // Parent wants to render. Check if it is blocked by any child.
                        boolean parentBlocked = false;
                        int childLvl = lvl; // Parent's child level is MY level
                        double childThreshold = getThreshold(childLvl, lod0Dist, lod1Dist, lod2Dist, lod3Dist);
                        int cSize = 32 * (1 << childLvl);

                        // Iterate parent's children (siblings + me)
                        for (int dx2 = 0; dx2 <= 1; dx2++) {
                            for (int dy2 = 0; dy2 <= 1; dy2++) {
                                for (int dz2 = 0; dz2 <= 1; dz2++) {
                                    int cx2 = px * 2 + dx2;
                                    int cy2 = py * 2 + dy2;
                                    int cz2 = pz * 2 + dz2;
                                    long siblingId = WorldEngine.getWorldSectionId(childLvl, cx2, cy2, cz2);
                                    if (availableSections.contains(siblingId)) {
                                        double cwx = (cx2 * cSize) + (cSize / 2.0);
                                        double cwz = (cz2 * cSize) + (cSize / 2.0);
                                        double cdx = cx - cwx;
                                        double cdz = cz - cwz;
                                        if (Math.hypot(cdx, cdz) < childThreshold) {
                                            parentBlocked = true;
                                            dx2 = 2; dy2 = 2; dz2 = 2;
                                        }
                                    }
                                }
                            }
                        }
                        parentEffective = !parentBlocked;
                    }
                }

                if (!parentEffective) {
                    sectionsToMesh.add(sectionId);
                    fallbackMeshed++;
                }
            }
        }
        if (fallbackMeshed > 0) {
            VoxelBridgeLogger.info(LogModule.LOD, "[LOD] fallback meshed sections=" + fallbackMeshed);
        }
        if (sectionsIntersecting == 0) {
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] no sections intersected the selection AABB");
        }

        LodGpuBakeService gpuBake = null;
        try {
            IntOpenHashSet blockIds = collectBlockIds(engine, sectionsToMesh);
            if (!blockIds.isEmpty()) {
                gpuBake = new LodGpuBakeService(engine.getMapper(), ctx);
                gpuBake.bakeBlockIds(blockIds);
            }
            VoxelMesher mesher = new VoxelMesher(engine, countingSink, offsetX, offsetY, offsetZ, gpuBake, ctx);
            for (long sectionId : sectionsToMesh) {
                int lvl = WorldEngine.getLevel(sectionId);
                int x = WorldEngine.getX(sectionId);
                int y = WorldEngine.getY(sectionId);
                int z = WorldEngine.getZ(sectionId);
                mesher.meshChunk(x, y, z, lvl);
                meshed[0]++;
            }
        } finally {
            if (gpuBake != null) {
                gpuBake.close();
            }
            engine.free();
        }

        VoxelBridgeLogger.info(LogModule.LOD, "[LOD] append done, visitedSections=" + visited[0] + ", meshedSections=" + meshed[0]);
        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] append stats: quads=%d, materials=%d, sprites=%d, overlays=%d",
            countingSink.getQuadCount(),
            countingSink.getMaterialCount(),
            countingSink.getSpriteCount(),
            countingSink.getOverlaySpriteCount()
        ));
        if (meshed[0] > 0 && countingSink.getQuadCount() == 0) {
            VoxelBridgeLogger.warn(LogModule.LOD, "[LOD] append produced 0 quads (all air or fully culled)");
        }
        if (requireGeometry && meshed[0] == 0) {
            throw new IllegalStateException("LOD export produced no geometry (selection empty or not imported)");
        }
        return meshed[0];
    }

    private static int getRequiredLevel(double dist, double d0, double d1, double d2, double d3) {
        if (!ExportRuntimeConfig.isLodEnabled()) return 0;
        if (dist < d0) return 0;
        if (dist < d1) return 1;
        if (dist < d2) return 2;
        if (dist < d3) return 3;
        return 4;
    }

    private static double getThreshold(int level, double d0, double d1, double d2, double d3) {
        if (level == 0) return d0;
        if (level == 1) return d1;
        if (level == 2) return d2;
        if (level == 3) return d3;
        return Double.MAX_VALUE;
    }

    private static boolean intersectsAabb(double sx, double sy, double sz, int size, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());

        double ex = sx + size;
        double ey = sy + size;
        double ez = sz + size;
        return sx <= maxX && ex >= minX && sy <= maxY && ey >= minY && sz <= maxZ && ez >= minZ;
    }

    private static IntOpenHashSet collectBlockIds(WorldEngine engine, java.util.List<Long> sectionsToMesh) {
        IntOpenHashSet ids = new IntOpenHashSet();
        for (long sectionId : sectionsToMesh) {
            int lvl = WorldEngine.getLevel(sectionId);
            int x = WorldEngine.getX(sectionId);
            int y = WorldEngine.getY(sectionId);
            int z = WorldEngine.getZ(sectionId);
            com.voxelbridge.voxy.common.world.WorldSection section = engine.acquire(lvl, x, y, z);
            if (section == null) {
                continue;
            }
            try {
                long[] data = section.copyData();
                for (long entry : data) {
                    if (Mapper.isAir(entry)) {
                        continue;
                    }
                    ids.add(Mapper.getBlockId(entry));
                }
            } finally {
                section.release();
            }
        }
        return ids;
    }

    private static final class CountingSceneSink implements SceneSink {
        private final SceneSink delegate;
        private final java.util.concurrent.atomic.LongAdder quadCount = new java.util.concurrent.atomic.LongAdder();
        private final java.util.Set<String> materialKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> spriteKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> overlaySpriteKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private CountingSceneSink(SceneSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void addQuad(String materialGroupKey, String spriteKey, String overlaySpriteKey, float[] positions,
                            float[] uv0, float[] uv1, float[] normal, float[] colors, boolean doubleSided) {
            quadCount.increment();
            if (materialGroupKey != null) materialKeys.add(materialGroupKey);
            if (spriteKey != null) spriteKeys.add(spriteKey);
            if (overlaySpriteKey != null) overlaySpriteKeys.add(overlaySpriteKey);
            delegate.addQuad(materialGroupKey, spriteKey, overlaySpriteKey, positions, uv0, uv1, normal, colors, doubleSided);
        }

        @Override
        public void onChunkStart(int chunkX, int chunkZ) {
            delegate.onChunkStart(chunkX, chunkZ);
        }

        @Override
        public void onChunkEnd(int chunkX, int chunkZ, boolean successful) {
            delegate.onChunkEnd(chunkX, chunkZ, successful);
        }

        @Override
        public Path write(SceneWriteRequest request) throws java.io.IOException {
            return delegate.write(request);
        }

        long getQuadCount() {
            return quadCount.sum();
        }

        int getMaterialCount() {
            return materialKeys.size();
        }

        int getSpriteCount() {
            return spriteKeys.size();
        }

        int getOverlaySpriteCount() {
            return overlaySpriteKeys.size();
        }
    }
}
