package com.voxelbridge.voxy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import com.voxelbridge.export.scene.gltf.GltfSceneSink;
import com.voxelbridge.voxy.common.config.section.MemorySectionStorage;
import com.voxelbridge.voxy.common.thread.ServiceManager;
import com.voxelbridge.voxy.common.world.WorldEngine;
import com.voxelbridge.voxy.importer.WorldImporter;
import com.voxelbridge.voxy.mesh.VoxelMesher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class OfflineExportCommand {
    private record ParsedArgs(String regionPath, boolean lodEnabled) {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vb_offline")
            .then(Commands.argument("region_path", StringArgumentType.greedyString())
                .executes(OfflineExportCommand::runExport)));
    }

    private static int runExport(CommandContext<CommandSourceStack> context) {
        String pathStrRaw = StringArgumentType.getString(context, "region_path");
        ParsedArgs parsed = parseArgs(pathStrRaw);
        File regionDir = new File(parsed.regionPath());
        CommandSourceStack source = context.getSource();

        if (!regionDir.exists() || !regionDir.isDirectory()) {
            source.sendFailure(Component.literal("Region directory not found: " + parsed.regionPath()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Starting offline export from: " + parsed.regionPath() + " (LOD " + (parsed.lodEnabled() ? "ON" : "OFF") + ")"), true);

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Setup Infrastructure
                ServiceManager serviceManager = new ServiceManager(i -> {});
                MemorySectionStorage storage = new MemorySectionStorage();
                WorldEngine engine = new WorldEngine(storage);
                engine.setSaveCallback((eng, section) -> storage.saveSection(section));
                engine.setDirtyCallback((section, flags, neighbors) -> storage.saveSection(section));
                
                // 2. Setup Importer
                WorldImporter importer = new WorldImporter(engine, source.getLevel(), serviceManager, () -> true);
                
                // 3. Import Data
                source.sendSystemMessage(Component.literal("Step 1: Importing .mca files..."));
                
                // Use a sync/blocking approach for simplicity in this command wrapper
                // Or implement a blocking wait on the callback
                CompletableFuture<Void> importFuture = new CompletableFuture<>();
                importer.importRegionDirectoryAsync(regionDir);
                importer.runImport(
                    (finished, total) -> {
                        if (finished % 100 == 0) source.sendSystemMessage(Component.literal("Imported " + finished + "/" + total));
                    },
                    (total) -> {
                        source.sendSystemMessage(Component.literal("Import complete! Total chunks: " + total));
                        importFuture.complete(null);
                    }
                );
                importFuture.join();
                importer.shutdown();

                // 4. Mesh and Export
                source.sendSystemMessage(Component.literal("Step 2: Meshing and Exporting..."));
                Path exportPath = Path.of("offline_export.glb");
                String fileName = exportPath.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
                Path outputDir = exportPath.getParent() != null ? exportPath.getParent() : Path.of(".");
                SceneWriteRequest request = new SceneWriteRequest(baseName, outputDir, exportPath);

                try (GltfSceneSink sink = new GltfSceneSink(request)) {
                    VoxelMesher mesher = new VoxelMesher(engine, sink);
                    
                    // Define LOD distances (squared for performance)
                    double lod0DistSq = 128 * 128;
                    double lod1DistSq = 256 * 256;
                    double lod2DistSq = 512 * 512;
                    double lod3DistSq = 1024 * 1024;
                    
                    var centerPos = source.getPosition();
                    double cx = centerPos.x;
                    double cy = centerPos.y;
                    double cz = centerPos.z;

                    // Iterate over all loaded sections and mesh them based on LOD
                    // Note: WorldEngine tracks sections via ActiveSectionTracker.
                    // Since we used MemorySectionStorage, we can iterate storage directly to find populated chunks.
                    storage.iterateStoredSectionPositions((long sectionId) -> {
                        int lvl = WorldEngine.getLevel(sectionId);
                        int x = WorldEngine.getX(sectionId);
                        int y = WorldEngine.getY(sectionId);
                        int z = WorldEngine.getZ(sectionId);
                        
                        // Calculate section center in world space
                        int scale = 1 << lvl;
                        int size = 32 * scale;
                        double wx = (x * size) + (size / 2.0);
                        double wy = (y * size) + (size / 2.0);
                        double wz = (z * size) + (size / 2.0);
                        
                        double dx = cx - wx;
                        double dz = cz - wz;
                        double distSq = dx * dx + dz * dz;
                        
                        // Determine required LOD for this distance
                        int requiredLvl;
                        if (!parsed.lodEnabled()) {
                            requiredLvl = 0;
                        } else if (distSq < lod0DistSq) {
                            requiredLvl = 0;
                        } else if (distSq < lod1DistSq) {
                            requiredLvl = 1;
                        } else if (distSq < lod2DistSq) {
                            requiredLvl = 2;
                        } else if (distSq < lod3DistSq) {
                            requiredLvl = 3;
                        } else {
                            requiredLvl = 4;
                        }
                        
                        // Only mesh if this section is the correct level for its distance
                        if (lvl == requiredLvl) {
                            mesher.meshChunk(x, y, z, lvl);
                        }
                    });
                    
                    sink.write(request);
                }

                source.sendSuccess(() -> Component.literal("Export finished! Saved to: " + exportPath.toAbsolutePath()), true);
                
                // Cleanup
                engine.free();

            } catch (Exception e) {
                e.printStackTrace();
                source.sendFailure(Component.literal("Export failed: " + e.getMessage()));
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static ParsedArgs parseArgs(String raw) {
        String trimmed = raw.trim();
        boolean lodEnabled = true;

        // Parse optional trailing flags like "--nolod" or "--lod=off".
        String[] parts = trimmed.split("\\s+");
        if (parts.length > 1) {
            String last = parts[parts.length - 1].toLowerCase();
            if (last.equals("--nolod") || last.equals("--lod=off")) {
                lodEnabled = false;
                // Strip the last token from the command string.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(parts[i]);
                }
                trimmed = sb.toString();
            }
        }
        return new ParsedArgs(trimmed, lodEnabled);
    }
}

