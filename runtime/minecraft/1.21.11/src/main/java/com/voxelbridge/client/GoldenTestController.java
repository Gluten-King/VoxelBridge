package com.voxelbridge.client;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.thread.ExportThread;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

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
    private static boolean productionJarVerified;
    private static String productionJarSha256 = "";
    private static String productionCodeSource = "";
    private static long exportStartNanos = -1L;
    private static long exportBaselineHeapBytes;
    private static long peakHeapUsedBytes;

    private GoldenTestController() {}

    public static void onClientTick(Minecraft minecraft) {
        if (!ENABLED || state == State.FINISHED) {
            return;
        }
        try {
            if (state == State.EXPORTING) {
                sampleHeapUsage();
            }
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

        verifyProductionJar();

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
        exportBaselineHeapBytes = usedHeapBytes();
        peakHeapUsedBytes = exportBaselineHeapBytes;
        exportStartNanos = System.nanoTime();
        ExportControl.ExportResult result = ExportControl.startExport(minecraft.level);
        if (!result.started()) {
            throw new IllegalStateException(result.message());
        }
        exportThread = ExportControl.getCurrentExport();
        if (exportThread == null) {
            throw new IllegalStateException("Export thread was not created");
        }
        VoxelBridgeLogger.probeEvent("export-start", Map.of(
                "pos1", pos1.getX() + "," + pos1.getY() + "," + pos1.getZ(),
                "pos2", pos2.getX() + "," + pos2.getY() + "," + pos2.getZ(),
                "scenario", System.getProperty("voxelbridge.golden.scenarioFile", "")));
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
            VoxelBridgeLogger.probeEvent("export-complete", Map.of(
                    "gltf", exportThread.getResultFile().toAbsolutePath().normalize().toString()));
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
        ExportRuntimeConfig.setLoggingEnabled(Boolean.parseBoolean(
                System.getProperty("voxelbridge.golden.logging", "true")));
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
        sampleHeapUsage();
        try {
            Path resultFile = requiredPath("voxelbridge.golden.resultFile");
            Path parent = resultFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String status = error == null ? "passed" : "failed";
            long clientDurationMillis = Duration.ofNanos(System.nanoTime() - START_NANOS).toMillis();
            long exportDurationMillis = exportStartNanos < 0L
                    ? 0L
                    : Duration.ofNanos(System.nanoTime() - exportStartNanos).toMillis();
            long peakHeapDeltaBytes = Math.max(0L, peakHeapUsedBytes - exportBaselineHeapBytes);
            String json = "{\n"
                    + "  \"status\": \"" + status + "\",\n"
                    + "  \"gltf\": " + jsonString(gltf == null ? "" : gltf.toAbsolutePath().normalize().toString()) + ",\n"
                    + "  \"error\": " + jsonString(error == null ? "" : error) + ",\n"
                    + "  \"productionJarVerified\": " + productionJarVerified + ",\n"
                    + "  \"jarSha256\": " + jsonString(productionJarSha256) + ",\n"
                    + "  \"codeSource\": " + jsonString(productionCodeSource) + ",\n"
                    + "  \"durationMillis\": " + exportDurationMillis + ",\n"
                    + "  \"exportDurationMillis\": " + exportDurationMillis + ",\n"
                    + "  \"clientDurationMillis\": " + clientDurationMillis + ",\n"
                    + "  \"peakHeapUsedBytes\": " + peakHeapUsedBytes + ",\n"
                    + "  \"peakHeapDeltaBytes\": " + peakHeapDeltaBytes + "\n"
                    + "}\n";
            Files.writeString(resultFile, json, StandardCharsets.UTF_8);
        } catch (Throwable reportFailure) {
            reportFailure.printStackTrace();
        }

        if (AUTO_STOP) {
            minecraft.stop();
        }
    }

    private static void sampleHeapUsage() {
        if (exportStartNanos < 0L) return;
        peakHeapUsedBytes = Math.max(peakHeapUsedBytes, usedHeapBytes());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing system property -D" + property + "=<path>");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void verifyProductionJar() throws IOException {
        if (productionJarVerified
                || !Boolean.getBoolean("voxelbridge.golden.requireProductionJar")) {
            return;
        }
        Path expected = requiredPath("voxelbridge.golden.expectedJar");
        if (!Files.isRegularFile(expected)
                || !expected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalStateException("Expected production JAR does not exist: " + expected);
        }

        Class<?> productionClass;
        try {
            productionClass = Class.forName("com.voxelbridge.VoxelBridge");
        } catch (ClassNotFoundException missingProductionMod) {
            throw new IllegalStateException("VoxelBridge production entrypoint is not loaded", missingProductionMod);
        }

        Path codeSource = null;
        try {
            if (productionClass.getProtectionDomain().getCodeSource() != null) {
                codeSource = Path.of(productionClass.getProtectionDomain()
                                .getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // NeoForge's union file system may expose a synthetic root here.
        }

        String resourceName = "/" + productionClass.getName().replace('.', '/') + ".class";
        URL classResource = productionClass.getResource(resourceName);
        Path resourceJar = resolveJarFromResource(classResource);
        boolean exactJar = sameFile(codeSource, expected) || sameFile(resourceJar, expected);
        boolean exactControllerBytes = false;
        if (!exactJar && classResource != null) {
            try (InputStream loadedClass = classResource.openStream();
                 ZipFile expectedArchive = new ZipFile(expected.toFile())) {
                var expectedEntry = expectedArchive.getEntry(resourceName.substring(1));
                if (expectedEntry != null) {
                    try (InputStream expectedClass = expectedArchive.getInputStream(expectedEntry)) {
                        exactControllerBytes = sha256(loadedClass).equals(sha256(expectedClass));
                    }
                }
            }
        }

        productionCodeSource = resourceJar != null
                ? resourceJar.toString()
                : (classResource != null ? classResource.toExternalForm() : String.valueOf(codeSource));
        if (!exactJar && !exactControllerBytes) {
            throw new IllegalStateException(
                    "Golden test loaded the wrong production code. expected=" + expected
                            + ", codeSource=" + codeSource + ", classResource=" + classResource);
        }
        productionJarSha256 = sha256(expected);
        productionJarVerified = true;
    }

    private static Path resolveJarFromResource(URL resource) {
        if (resource == null) {
            return null;
        }
        try {
            String external = URLDecoder.decode(resource.toExternalForm(), StandardCharsets.UTF_8);
            int jarEnd = external.toLowerCase(Locale.ROOT).indexOf(".jar");
            if (jarEnd < 0) {
                return null;
            }
            String candidate = external.substring(0, jarEnd + 4);
            while (candidate.startsWith("jar:") || candidate.startsWith("union:")) {
                candidate = candidate.substring(candidate.indexOf(':') + 1);
            }
            if (candidate.startsWith("file:")) {
                candidate = candidate.substring("file:".length());
            }
            if (candidate.matches("^/[A-Za-z]:/.*")) {
                candidate = candidate.substring(1);
            }
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean sameFile(Path first, Path second) {
        if (first == null || second == null
                || !Files.isRegularFile(first) || !Files.isRegularFile(second)) {
            return false;
        }
        try {
            return Files.isSameFile(first, second);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
