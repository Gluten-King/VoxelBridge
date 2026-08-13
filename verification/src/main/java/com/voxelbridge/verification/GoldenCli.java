package com.voxelbridge.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GoldenCli {
    private GoldenCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        String command = args[0];
        Map<String, String> options = parseOptions(args);
        Path gltf = requiredPath(options, "--gltf");
        String scenario = options.getOrDefault("--scenario", "unknown");
        String minecraft = options.getOrDefault("--minecraft", "unknown");
        Path scenarioFile = optionalPath(options, "--scenario-file");
        Path scenarioManifest = optionalPath(options, "--scenario-manifest");

        GoldenSnapshot actual = SemanticGltfAnalyzer.analyze(
                gltf, scenario, minecraft, scenarioFile, scenarioManifest, 1.0e-5);

        switch (command) {
            case "generate" -> {
                Path snapshot = requiredPath(options, "--snapshot");
                GoldenJson.write(snapshot, actual);
                System.out.println("Golden snapshot written: " + snapshot.toAbsolutePath());
            }
            case "verify" -> {
                Path expectedPath = requiredPath(options, "--expected");
                Path actualPath = requiredPath(options, "--actual");
                GoldenJson.write(actualPath, actual);
                if (!Files.isRegularFile(expectedPath)) {
                    throw new IllegalStateException("Expected golden snapshot does not exist: " + expectedPath);
                }
                GoldenSnapshot expected = GoldenJson.read(expectedPath);
                if (!expected.equals(actual)) {
                    throw new AssertionError(
                            "Golden snapshot mismatch.\nExpected: " + expectedPath.toAbsolutePath()
                                    + "\nActual:   " + actualPath.toAbsolutePath()
                                    + "\n\nActual snapshot:\n" + GoldenJson.pretty(actual));
                }
                System.out.println("Golden snapshot verified: " + expectedPath.toAbsolutePath());
            }
            case "inspect" -> System.out.println(GoldenJson.pretty(actual));
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --name value pair near argument " + i);
            }
            result.put(args[i], args[i + 1]);
        }
        return result;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option " + name);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path optionalPath(Map<String, String> options, String name) {
        String value = options.get(name);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  generate --gltf FILE --snapshot FILE --scenario ID --minecraft VERSION"
                + " [--scenario-file FILE] [--scenario-manifest FILE]");
        System.err.println("  verify   --gltf FILE --expected FILE --actual FILE --scenario ID --minecraft VERSION"
                + " [--scenario-file FILE] [--scenario-manifest FILE]");
        System.err.println("  inspect  --gltf FILE --scenario ID --minecraft VERSION"
                + " [--scenario-file FILE] [--scenario-manifest FILE]");
    }
}
