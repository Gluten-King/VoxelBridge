package com.voxelbridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent storage for export runtime configuration.
 * Backed by a JSON file in the game config directory.
 */
public final class ExportConfigStore {
    private static final String FILE_NAME = "voxelbridge.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile boolean suppressSave = false;

    private ExportConfigStore() {
    }

    public static void init() {
        ExportRuntimeConfig.setChangeListener(ExportConfigStore::onRuntimeConfigChanged);
        load();
    }

    public static void load() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        if (!Files.exists(path)) {
            save();
            return;
        }
        suppressSave = true;
        try (Reader reader = Files.newBufferedReader(path)) {
            ExportConfigData data = GSON.fromJson(reader, ExportConfigData.class);
            if (data != null) {
                applyToRuntime(data);
            }
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.EXPORT, "[Config] Failed to load " + path + ": " + e.getMessage());
        } finally {
            suppressSave = false;
        }
        VoxelBridgeLogger.setEnabled(ExportRuntimeConfig.isLoggingEnabled());
    }

    public static void save() {
        Path path = getConfigPath();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            VoxelBridgeLogger.warn(LogModule.EXPORT, "[Config] Failed to create config directory: " + e.getMessage());
            return;
        }
        ExportConfigData data = fromRuntime();
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            VoxelBridgeLogger.warn(LogModule.EXPORT, "[Config] Failed to save " + path + ": " + e.getMessage());
        }
    }

    private static void onRuntimeConfigChanged() {
        if (suppressSave) {
            return;
        }
        VoxelBridgeLogger.setEnabled(ExportRuntimeConfig.isLoggingEnabled());
        save();
    }

    private static ExportConfigData fromRuntime() {
        ExportConfigData data = new ExportConfigData();
        data.atlasMode = ExportRuntimeConfig.getAtlasMode().name();
        data.atlasSize = ExportRuntimeConfig.getAtlasSize().getSize();
        data.atlasPadding = ExportRuntimeConfig.getAtlasPadding();
        data.colorMode = ExportRuntimeConfig.getColorMode().name();
        data.coordinateMode = ExportRuntimeConfig.getCoordinateMode().name();
        data.exportThreadCount = ExportRuntimeConfig.getExportThreadCount();
        data.vanillaRandomTransformEnabled = ExportRuntimeConfig.isVanillaRandomTransformEnabled();
        data.animationEnabled = ExportRuntimeConfig.isAnimationEnabled();
        data.fillCaveEnabled = ExportRuntimeConfig.isFillCaveEnabled();
        data.pbrDecodeEnabled = ExportRuntimeConfig.isPbrDecodeEnabled();
        data.loggingEnabled = ExportRuntimeConfig.isLoggingEnabled();
        data.exportDoubleSidedEnabled = ExportRuntimeConfig.isExportDoubleSidedEnabled();
        data.nonsolidCullingEnabled = ExportRuntimeConfig.isNonsolidCullingEnabled();
        data.selectionLineWidth = ExportRuntimeConfig.getSelectionLineWidth();
        return data;
    }

    private static void applyToRuntime(ExportConfigData data) {
        if (data.atlasMode != null) {
            try {
                ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.valueOf(data.atlasMode));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (data.atlasSize > 0) {
            ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.fromSize(data.atlasSize));
        }
        if (data.atlasPadding >= 0) {
            ExportRuntimeConfig.setAtlasPadding(data.atlasPadding);
        }
        if (data.colorMode != null) {
            try {
                ExportRuntimeConfig.setColorMode(com.voxelbridge.core.util.color.ColorMode.valueOf(data.colorMode));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (data.coordinateMode != null) {
            try {
                ExportRuntimeConfig.setCoordinateMode(com.voxelbridge.export.CoordinateMode.valueOf(data.coordinateMode));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (data.exportThreadCount > 0) {
            ExportRuntimeConfig.setExportThreadCount(data.exportThreadCount);
        }
        ExportRuntimeConfig.setVanillaRandomTransformEnabled(data.vanillaRandomTransformEnabled);
        ExportRuntimeConfig.setAnimationEnabled(data.animationEnabled);
        ExportRuntimeConfig.setFillCaveEnabled(data.fillCaveEnabled);
        ExportRuntimeConfig.setPbrDecodeEnabled(data.pbrDecodeEnabled);
        ExportRuntimeConfig.setLoggingEnabled(data.loggingEnabled);
        if (data.exportDoubleSidedEnabled != null) {
            ExportRuntimeConfig.setExportDoubleSidedEnabled(data.exportDoubleSidedEnabled);
        }
        if (data.nonsolidCullingEnabled != null) {
            ExportRuntimeConfig.setNonsolidCullingEnabled(data.nonsolidCullingEnabled);
        } else if (data.cullNonsolidAgainstSolidEnabled != null) {
            ExportRuntimeConfig.setNonsolidCullingEnabled(data.cullNonsolidAgainstSolidEnabled);
        }
        if (data.selectionLineWidth != null) {
            ExportRuntimeConfig.setSelectionLineWidth(data.selectionLineWidth);
        }
    }

    private static Path getConfigPath() {
        Minecraft mc = ClientAccessHolder.get().getMinecraft();
        if (mc == null || mc.gameDirectory == null) {
            return null;
        }
        return mc.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static final class ExportConfigData {
        String atlasMode;
        int atlasSize;
        int atlasPadding;
        String colorMode;
        String coordinateMode;
        int exportThreadCount;
        boolean vanillaRandomTransformEnabled;
        boolean animationEnabled;
        boolean fillCaveEnabled;
        boolean pbrDecodeEnabled;
        boolean loggingEnabled;
        Boolean exportDoubleSidedEnabled;
        Boolean nonsolidCullingEnabled;
        Integer selectionLineWidth;
        // Backward compatibility for older config key.
        Boolean cullNonsolidAgainstSolidEnabled;
    }
}
