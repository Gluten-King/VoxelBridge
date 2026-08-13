package com.voxelbridge.verification;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates immutable launcher-world archives and records their NBT DataVersion. */
public final class GoldenWorldCli {
    private GoldenWorldCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected record or validate");
        }
        Map<String, String> options = options(args);
        if (args[0].equals("record")) {
            record(options);
        } else if (args[0].equals("validate")) {
            validate(options);
        } else {
            throw new IllegalArgumentException("Unknown world command " + args[0]);
        }
    }

    private static void record(Map<String, String> options) throws Exception {
        Path zip = requiredPath(options, "--zip");
        Path manifestPath = requiredPath(options, "--manifest");
        String minecraft = required(options, "--minecraft");
        WorldSummary summary = inspect(zip);
        Map<String, Object> manifest = Files.isRegularFile(manifestPath)
                ? GoldenJson.readMap(manifestPath)
                : new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worlds = new ArrayList<>((List<Map<String, Object>>)
                manifest.getOrDefault("worlds", List.of()));
        worlds.removeIf(world -> zip.getFileName().toString().equals(world.get("archive")));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("archive", zip.getFileName().toString());
        entry.put("minecraft", minecraft);
        entry.put("platform", options.getOrDefault("--platform", "shared"));
        entry.put("dataVersion", summary.dataVersion());
        entry.put("seed", summary.seed());
        entry.put("generator", summary.flatGenerator() ? "minecraft:flat" : "unknown");
        entry.put("sha256", GoldenReviewCli.hashArtifact(zip));
        entry.put("capturedAt", options.getOrDefault("--captured-at", Instant.now().toString()));
        worlds.add(entry);
        worlds.sort((a, b) -> String.valueOf(a.get("archive")).compareTo(String.valueOf(b.get("archive"))));
        manifest.put("worlds", worlds);
        GoldenJson.writeValue(manifestPath, manifest);
        System.out.println("Recorded golden world " + zip + " DataVersion=" + summary.dataVersion());
    }

    private static void validate(Map<String, String> options) throws Exception {
        Path directory = requiredPath(options, "--worlds");
        Path manifestPath = requiredPath(options, "--manifest");
        Map<String, Object> manifest = GoldenJson.readMap(manifestPath);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worlds = (List<Map<String, Object>>) manifest.getOrDefault("worlds", List.of());
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> entry : worlds) {
            Path zip = directory.resolve(String.valueOf(entry.get("archive")));
            if (!Files.isRegularFile(zip)) {
                failures.add("missing " + zip.getFileName());
                continue;
            }
            String hash = GoldenReviewCli.hashArtifact(zip);
            if (!hash.equalsIgnoreCase(String.valueOf(entry.get("sha256")))) {
                failures.add(zip.getFileName() + " SHA-256 mismatch");
                continue;
            }
            WorldSummary summary = inspect(zip);
            if (summary.dataVersion() != ((Number) entry.get("dataVersion")).intValue()) {
                failures.add(zip.getFileName() + " DataVersion mismatch");
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Golden world validation failed:\n  " + String.join("\n  ", failures));
        }
        System.out.println("Validated " + worlds.size() + " golden world archive(s).");
    }

    static WorldSummary inspect(Path zipPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry level = zip.getEntry("level.dat");
            if (level == null || level.isDirectory()) {
                throw new IOException("World zip must contain level.dat at its root: " + zipPath);
            }
            try (InputStream raw = zip.getInputStream(level);
                 DataInputStream input = new DataInputStream(new GZIPInputStream(raw))) {
                int rootType = input.readUnsignedByte();
                if (rootType != 10) {
                    throw new IOException("level.dat root must be an NBT compound");
                }
                input.readUTF();
                MutableSummary summary = new MutableSummary();
                readCompound(input, summary);
                if (summary.dataVersion < 0) {
                    throw new IOException("level.dat has no DataVersion");
                }
                return new WorldSummary(summary.dataVersion, summary.seed, summary.flatGenerator);
            }
        }
    }

    private static void readCompound(DataInputStream input, MutableSummary summary) throws IOException {
        while (true) {
            int type;
            try {
                type = input.readUnsignedByte();
            } catch (EOFException truncated) {
                throw new IOException("Truncated NBT compound", truncated);
            }
            if (type == 0) {
                return;
            }
            String name = input.readUTF();
            readPayload(input, type, name, summary);
        }
    }

    private static void readPayload(
            DataInputStream input, int type, String name, MutableSummary summary) throws IOException {
        switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> {
                int value = input.readInt();
                if (name.equals("DataVersion")) {
                    summary.dataVersion = value;
                }
            }
            case 4 -> {
                long value = input.readLong();
                if (name.equalsIgnoreCase("seed") || name.equals("RandomSeed")) {
                    summary.seed = value;
                }
            }
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> skipFully(input, checkedLength(input.readInt(), 1));
            case 8 -> {
                String value = input.readUTF();
                if (name.equals("type") && value.equals("minecraft:flat")) {
                    summary.flatGenerator = true;
                }
            }
            case 9 -> {
                int childType = input.readUnsignedByte();
                int length = checkedLength(input.readInt(), 1);
                for (int index = 0; index < length; index++) {
                    readPayload(input, childType, "", summary);
                }
            }
            case 10 -> readCompound(input, summary);
            case 11 -> skipFully(input, checkedLength(input.readInt(), Integer.BYTES));
            case 12 -> skipFully(input, checkedLength(input.readInt(), Long.BYTES));
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int checkedLength(int elements, int bytesPerElement) throws IOException {
        if (elements < 0 || elements > Integer.MAX_VALUE / bytesPerElement) {
            throw new IOException("Invalid NBT array length " + elements);
        }
        return elements * bytesPerElement;
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) {
                throw new EOFException("Truncated NBT array");
            }
            remaining -= skipped;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value near " + index);
            }
            result.put(args[index], args[index + 1]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        return Path.of(required(options, name)).toAbsolutePath().normalize();
    }

    record WorldSummary(int dataVersion, long seed, boolean flatGenerator) {}

    private static final class MutableSummary {
        int dataVersion = -1;
        long seed;
        boolean flatGenerator;
    }
}
