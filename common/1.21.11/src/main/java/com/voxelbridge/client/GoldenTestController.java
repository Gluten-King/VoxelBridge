package com.voxelbridge.client;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.thread.ExportThread;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Opt-in client automation used by golden tests. It is inert unless
 * {@code -Dvoxelbridge.golden.enabled=true} is supplied to the game JVM.
 */
public final class GoldenTestController {
    private enum State { WAITING_FOR_WORLD, RUNNING_COMMANDS, SETTLING, EXPORTING, FINISHED }

    private static final boolean ENABLED = Boolean.getBoolean("voxelbridge.golden.enabled");
    private static final long START_NANOS = System.nanoTime();
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(
            Long.getLong("voxelbridge.golden.timeoutSeconds", 300L)).toNanos();
    private static final int SETTLE_TICKS = Integer.getInteger("voxelbridge.golden.settleTicks", 40);
    private static final boolean AUTO_STOP = Boolean.parseBoolean(
            System.getProperty("voxelbridge.golden.autoStop", "true"));

    private static State state = State.WAITING_FOR_WORLD;
    private static final ArrayDeque<String> commands = new ArrayDeque<>();
    private static int settleTicksRemaining;
    private static ExportThread exportThread;

    private GoldenTestController() {}

    public static void onClientTick(Minecraft minecraft) {
        if (!ENABLED || state == State.FINISHED) {
            return;
        }
        try {
            if (System.nanoTime() - START_NANOS > TIMEOUT_NANOS) {
                ExportControl.abortExport();
                finish(minecraft, null, "Golden test timed out");
                return;
            }

            switch (state) {
                case WAITING_FOR_WORLD -> waitForWorld(minecraft);
                case RUNNING_COMMANDS -> runNextCommand(minecraft);
                case SETTLING -> settleAndStartExport(minecraft);
                case EXPORTING -> pollExport(minecraft);
                case FINISHED -> { }
            }
        } catch (Throwable failure) {
            ExportControl.abortExport();
            finish(minecraft, null, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static void waitForWorld(Minecraft minecraft) throws IOException {
        if (minecraft.level == null || minecraft.player == null || minecraft.player.connection == null) {
            return;
        }

        Path scenarioFile = requiredPath("voxelbridge.golden.scenarioFile");
        List<String> lines = Files.readAllLines(scenarioFile, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            addScenarioCommand(line.startsWith("/") ? line.substring(1) : line);
        }
        if (commands.isEmpty()) {
            throw new IOException("Golden scenario contains no commands: " + scenarioFile);
        }

        configureDeterministicExport();
        state = State.RUNNING_COMMANDS;
    }

    private static void runNextCommand(Minecraft minecraft) {
        String command = commands.pollFirst();
        if (command != null) {
            minecraft.player.connection.sendCommand(command);
            return;
        }
        settleTicksRemaining = Math.max(1, SETTLE_TICKS);
        state = State.SETTLING;
    }

    private static void settleAndStartExport(Minecraft minecraft) {
        if (--settleTicksRemaining > 0) {
            return;
        }

        BlockPos pos1 = parseBlockPos(System.getProperty("voxelbridge.golden.pos1", "0,60,0"));
        BlockPos pos2 = parseBlockPos(System.getProperty("voxelbridge.golden.pos2", "15,72,15"));
        ExportControl.setPos1(pos1);
        ExportControl.setPos2(pos2);
        ExportControl.ExportResult result = ExportControl.startExport(minecraft.level);
        if (!result.started()) {
            throw new IllegalStateException(result.message());
        }
        exportThread = ExportControl.getCurrentExport();
        if (exportThread == null) {
            throw new IllegalStateException("Export thread was not created");
        }
        state = State.EXPORTING;
    }

    private static void pollExport(Minecraft minecraft) {
        if (exportThread.isAlive()) {
            return;
        }
        if (exportThread.wasAborted()) {
            finish(minecraft, null, "Export was aborted");
        } else if (exportThread.getFailure() != null) {
            Throwable failure = exportThread.getFailure();
            finish(minecraft, null, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } else if (exportThread.getResultFile() == null) {
            finish(minecraft, null, "Export completed without a result file");
        } else {
            finish(minecraft, exportThread.getResultFile(), null);
        }
    }

    private static void configureDeterministicExport() {
        ExportRuntimeConfig.setExportThreadCount(Integer.getInteger(
                "voxelbridge.golden.exportThreadCount", 1));
        String atlasMode = System.getProperty(
                "voxelbridge.golden.atlasMode", "individual").trim();
        ExportRuntimeConfig.setAtlasMode(
                "atlas".equalsIgnoreCase(atlasMode)
                        ? ExportRuntimeConfig.AtlasMode.ATLAS
                        : ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
        ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_8192);
        ExportRuntimeConfig.setAtlasPadding(0);
        ExportRuntimeConfig.setColorMode(ColorMode.BOTH);
        String coordinateMode = System.getProperty(
                "voxelbridge.golden.coordinateMode", "centered").trim();
        ExportRuntimeConfig.setCoordinateMode(
                "world_origin".equalsIgnoreCase(coordinateMode)
                                || "world-origin".equalsIgnoreCase(coordinateMode)
                        ? CoordinateMode.WORLD_ORIGIN
                        : CoordinateMode.CENTERED);
        ExportRuntimeConfig.setVanillaRandomTransformEnabled(false);
        ExportRuntimeConfig.setAnimationEnabled(false);
        ExportRuntimeConfig.setFillCaveEnabled(false);
        ExportRuntimeConfig.setPbrDecodeEnabled(false);
        ExportRuntimeConfig.setExportDoubleSidedEnabled(Boolean.parseBoolean(
                System.getProperty("voxelbridge.golden.exportDoubleSided", "true")));
        ExportRuntimeConfig.setNonsolidCullingEnabled(Boolean.parseBoolean(
                System.getProperty("voxelbridge.golden.nonsolidCulling", "true")));
    }

    private static void addScenarioCommand(String command) {
        if (!"1.21.11".equals(System.getProperty(
                "voxelbridge.golden.minecraftVersion", "").trim())) {
            commands.addLast(command);
            return;
        }
        if (command.startsWith("gamerule doDaylightCycle ")) {
            commands.addLast(command.replace(
                    "gamerule doDaylightCycle ", "gamerule advance_time "));
        } else if (command.startsWith("gamerule doWeatherCycle ")) {
            commands.addLast(command.replace(
                    "gamerule doWeatherCycle ", "gamerule advance_weather "));
        } else if (command.startsWith("gamerule doMobSpawning ")) {
            String value = command.substring("gamerule doMobSpawning ".length());
            commands.addLast("gamerule spawn_mobs " + value);
            commands.addLast("gamerule spawn_monsters " + value);
        } else if (command.startsWith("gamerule randomTickSpeed ")) {
            commands.addLast(command.replace(
                    "gamerule randomTickSpeed ", "gamerule random_tick_speed "));
        } else {
            commands.addLast(command);
        }
    }

    private static void finish(Minecraft minecraft, Path gltf, String error) {
        if (state == State.FINISHED) {
            return;
        }
        state = State.FINISHED;
        try {
            Path resultFile = requiredPath("voxelbridge.golden.resultFile");
            Path parent = resultFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String status = error == null ? "passed" : "failed";
            String json = "{\n"
                    + "  \"status\": \"" + status + "\",\n"
                    + "  \"gltf\": " + jsonString(gltf == null ? "" : gltf.toAbsolutePath().normalize().toString()) + ",\n"
                    + "  \"error\": " + jsonString(error == null ? "" : error) + "\n"
                    + "}\n";
            Files.writeString(resultFile, json, StandardCharsets.UTF_8);
        } catch (Throwable reportFailure) {
            reportFailure.printStackTrace();
        }

        if (AUTO_STOP) {
            minecraft.stop();
        }
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing system property -D" + property + "=<path>");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static BlockPos parseBlockPos(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected x,y,z block position, got: " + value);
        }
        return new BlockPos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}
