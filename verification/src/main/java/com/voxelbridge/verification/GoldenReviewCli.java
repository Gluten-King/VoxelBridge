package com.voxelbridge.verification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Creates immutable, hash-bound golden run receipts and applies explicit human
 * review decisions. Reports are deliberately read-only; repository changes can
 * only be made by the approve command with an explicit confirmation flag.
 */
public final class GoldenReviewCli {
    private static final int RUN_SCHEMA = 1;
    private static final int REVIEW_SCHEMA = 1;
    private static final int BASELINE_SCHEMA = 1;
    private static final Set<String> REJECTION_CATEGORIES = Set.of(
            "geometry", "uv", "texture", "material", "culling", "consumer", "other");

    private GoldenReviewCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            throw new IllegalArgumentException("Missing command");
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        switch (command) {
            case "record" -> record(options);
            case "diff" -> diff(options);
            case "report" -> report(options);
            case "approve" -> decide(options, "approved");
            case "reject" -> decide(options, "rejected");
            case "waive" -> decide(options, "waived");
            case "verify-determinism" -> verifyDeterminism(options);
            case "verify-release" -> verifyRelease(options);
            default -> {
                usage();
                throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    private static void record(Map<String, String> options) throws Exception {
        Path repo = requiredPath(options, "--repo");
        Path runDir = requiredPath(options, "--run-dir");
        String target = required(options, "--target");
        String platform = required(options, "--platform");
        String minecraft = required(options, "--minecraft");
        String scenario = required(options, "--scenario");
        String variant = options.getOrDefault("--variant", "default");
        int repeat = Integer.parseInt(options.getOrDefault("--repeat", "1"));
        String runId = options.getOrDefault("--run-id", runDir.getFileName().toString());
        String caseId = target + "/" + scenario + "/" + variant;

        Path resultPath = path(options, "--result", runDir.resolve("result.json"));
        Path actualPath = path(options, "--actual", runDir.resolve("actual.snapshot.json"));
        Path scenarioManifest = optionalPath(options, "--scenario-manifest");
        Path scenarioFile = optionalPath(options, "--scenario-file");
        Path jar = optionalPath(options, "--jar");
        Path world = optionalPath(options, "--world");
        Path resourcePack = optionalPath(options, "--resource-pack");
        Path modPack = optionalPath(options, "--mod-pack");
        Path blenderConfig = optionalPath(options, "--blender-config");

        Map<String, Object> result = Files.isRegularFile(resultPath)
                ? GoldenJson.readMap(resultPath)
                : Map.of();
        String clientStatus = String.valueOf(result.getOrDefault("status", "error"));
        Path gltf = optionalPath(options, "--gltf");
        if (gltf == null) {
            String gltfValue = String.valueOf(result.getOrDefault("gltf", ""));
            if (!gltfValue.isBlank()) {
                gltf = Path.of(gltfValue).toAbsolutePath().normalize();
            }
        }

        Path baselineDir = baselineDir(repo, target, scenario, variant);
        Path canonicalBaseline = baselineDir.resolve("semantic.snapshot.json");
        Path legacyBaseline = repo.resolve("golden/expected")
                .resolve(minecraft)
                .resolve(variant.equals("default")
                        ? scenario + ".snapshot.json"
                        : scenario + "." + variant + ".snapshot.json");
        Path expected = optionalPath(options, "--baseline");
        if (expected == null) {
            expected = Files.isRegularFile(canonicalBaseline) ? canonicalBaseline : legacyBaseline;
        }

        String machineStatus;
        String machineDetail;
        Path semanticDiff = runDir.resolve("semantic.diff.json");
        Files.deleteIfExists(semanticDiff);
        if (!"passed".equals(clientStatus)) {
            machineStatus = "error";
            machineDetail = String.valueOf(result.getOrDefault("error", "client result is unavailable"));
        } else if (!Files.isRegularFile(actualPath)) {
            machineStatus = "error";
            machineDetail = "semantic snapshot is missing";
        } else if (Files.isRegularFile(expected) && !jsonEquals(expected, actualPath)) {
            machineStatus = "failed";
            SemanticSnapshotDiff.Result diff = SemanticSnapshotDiff.write(expected, actualPath, semanticDiff);
            machineDetail = "semantic snapshot differs from " + repo.relativize(expected)
                    + " (" + diff.detail() + ")";
        } else if (!Files.isRegularFile(expected)) {
            machineStatus = "passed";
            machineDetail = "new baseline requires review";
        } else {
            machineStatus = "passed";
            machineDetail = "semantic snapshot matches baseline";
        }
        if ("passed".equals(machineStatus) && scenarioManifest != null) {
            ProbeCheck probeCheck = verifyProbes(scenarioManifest, runDir.resolve("export/probe.jsonl"));
            if (!probeCheck.passed()) {
                machineStatus = "failed";
                machineDetail = probeCheck.detail();
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        putHash(hashes, "productionJar", jar);
        putHash(hashes, "scenarioManifest", scenarioManifest);
        putHash(hashes, "scenarioCommands", scenarioFile);
        putHash(hashes, "world", world);
        putHash(hashes, "resourcePack", resourcePack);
        putHash(hashes, "modPack", modPack);
        putHash(hashes, "semanticSnapshot", actualPath);
        putHash(hashes, "gltfBundle", gltf == null ? null : gltf.getParent());
        putHash(hashes, "blenderConfig", blenderConfig);
        hashes.put("verificationTool", options.getOrDefault("--tool-version", "workspace"));
        String artifactFingerprint = hashStrings(hashes);

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("os", System.getProperty("os.name"));
        environment.put("osVersion", System.getProperty("os.version"));
        environment.put("java", System.getProperty("java.version"));
        environment.put("gpu", options.getOrDefault("--gpu", "unknown"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", RUN_SCHEMA);
        manifest.put("runId", runId);
        manifest.put("caseId", caseId);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("gitCommit", options.getOrDefault("--git-commit", "unknown"));
        manifest.put("target", target);
        manifest.put("platform", platform);
        manifest.put("minecraft", minecraft);
        manifest.put("scenario", scenario);
        manifest.put("variant", variant);
        manifest.put("repeat", repeat);
        manifest.put("mods", splitCsv(options.get("--mods")));
        manifest.put("paths", pathMap(repo, runDir, jar, world, scenarioManifest,
                scenarioFile, actualPath, expected, gltf,
                Files.isRegularFile(semanticDiff) ? semanticDiff : null));
        manifest.put("hashes", hashes);
        manifest.put("artifactFingerprint", artifactFingerprint);
        manifest.put("environment", environment);
        Map<String, Object> metrics = new LinkedHashMap<>();
        Object exportDuration = result.getOrDefault(
                "exportDurationMillis", result.getOrDefault("durationMillis", 0));
        metrics.put("durationMillis", exportDuration);
        metrics.put("exportDurationMillis", exportDuration);
        metrics.put("clientDurationMillis", result.getOrDefault("clientDurationMillis", 0));
        metrics.put("peakHeapUsedBytes", result.getOrDefault("peakHeapUsedBytes", 0));
        metrics.put("peakHeapDeltaBytes", result.getOrDefault("peakHeapDeltaBytes", 0));
        manifest.put("metrics", metrics);
        GoldenJson.writeValue(runDir.resolve("run-manifest.json"), manifest);

        List<String> warnings = performanceWarnings(
                scenarioManifest, baselineDir.resolve("baseline.manifest.json"), metrics);

        String reviewStatus = "pending";
        String approvalSource = "none";
        Path approvalPath = baselineDir.resolve("approval.json");
        if (Files.isRegularFile(approvalPath)) {
            Map<String, Object> approval = GoldenJson.readMap(approvalPath);
            if (artifactFingerprint.equals(approval.get("artifactFingerprint"))) {
                reviewStatus = "approved";
                approvalSource = "baseline";
            } else {
                reviewStatus = "stale";
                approvalSource = "baseline_changed";
            }
        }
        if (!"passed".equals(machineStatus)) {
            reviewStatus = "pending";
            approvalSource = "none";
        }

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("schemaVersion", REVIEW_SCHEMA);
        review.put("runId", runId);
        review.put("caseId", caseId);
        review.put("target", target);
        review.put("scenario", scenario);
        review.put("variant", variant);
        review.put("machineStatus", machineStatus);
        review.put("machineDetail", machineDetail);
        review.put("reviewStatus", reviewStatus);
        review.put("overallStatus", overallStatus(machineStatus, reviewStatus));
        review.put("approvalSource", approvalSource);
        review.put("artifactFingerprint", artifactFingerprint);
        review.put("warnings", warnings);
        review.put("reviewer", null);
        review.put("reviewedAt", null);
        review.put("verdict", null);
        review.put("category", null);
        review.put("reason", null);
        review.put("issue", null);
        review.put("expires", null);
        GoldenJson.writeValue(runDir.resolve("review.json"), review);
        System.out.println(caseId + ": " + review.get("overallStatus") + " (" + machineDetail + ")");
    }

    private static void diff(Map<String, String> options) throws IOException {
        SemanticSnapshotDiff.Result result = SemanticSnapshotDiff.write(
                requiredPath(options, "--expected"),
                requiredPath(options, "--actual"),
                requiredPath(options, "--output"));
        System.out.println("Semantic snapshot diff: " + result.path());
        System.out.println(result.detail());
    }

    private static void decide(Map<String, String> options, String verdict) throws Exception {
        Path repo = requiredPath(options, "--repo");
        Path runs = requiredPath(options, "--runs");
        String selector = options.getOrDefault("--selector", "all-passed");
        String reviewer = options.getOrDefault("--reviewer", detectReviewer(repo));
        String reason = required(options, "--reason").trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("--reason must not be blank");
        }

        List<ReviewItem> selected = selectItems(
                filterRun(loadItems(runs), options.get("--run-id")), selector);
        if (selected.isEmpty()) {
            throw new IllegalStateException("Selector matched no review items: " + selector);
        }
        for (ReviewItem item : selected) {
            if ((verdict.equals("approved") || verdict.equals("waived"))
                    && !"passed".equals(item.review().get("machineStatus"))) {
                throw new IllegalStateException(
                        "Cannot " + verdict + " machine failure " + item.caseId());
            }
        }

        System.out.println("Review operation: " + verdict);
        for (ReviewItem item : selected) {
            System.out.println("  " + item.caseId() + "  "
                    + item.review().get("artifactFingerprint"));
        }
        if (!Boolean.parseBoolean(options.getOrDefault("--confirm", "false"))) {
            throw new IllegalStateException(
                    "Dry preview only. Re-run with --confirm true after checking the list above.");
        }

        String category = null;
        String issue = null;
        String expires = null;
        if (verdict.equals("rejected")) {
            category = required(options, "--category").toLowerCase(Locale.ROOT);
            if (!REJECTION_CATEGORIES.contains(category)) {
                throw new IllegalArgumentException(
                        "Invalid rejection category. Expected one of " + REJECTION_CATEGORIES);
            }
        } else if (verdict.equals("waived")) {
            issue = required(options, "--issue");
            expires = required(options, "--expires");
        }

        for (ReviewItem item : selected) {
            Map<String, Object> review = new LinkedHashMap<>(item.review());
            review.put("reviewStatus", verdict);
            review.put("overallStatus", overallStatus(
                    String.valueOf(review.get("machineStatus")), verdict));
            review.put("approvalSource", "manual");
            review.put("reviewer", reviewer);
            review.put("reviewedAt", Instant.now().toString());
            review.put("verdict", verdict);
            review.put("category", category);
            review.put("reason", reason);
            review.put("issue", issue);
            review.put("expires", expires);

            if (verdict.equals("approved")) {
                approveBaseline(repo, item, reviewer, reason);
            }
            GoldenJson.writeValue(item.directory().resolve("review.json"), review);
        }
    }

    private static void approveBaseline(
            Path repo, ReviewItem item, String reviewer, String reason) throws Exception {
        Map<String, Object> manifest = item.manifest();
        String target = String.valueOf(manifest.get("target"));
        String scenario = String.valueOf(manifest.get("scenario"));
        String variant = String.valueOf(manifest.get("variant"));
        Path baselineDir = baselineDir(repo, target, scenario, variant);
        Files.createDirectories(baselineDir);

        Map<String, Object> paths = objectMap(manifest.get("paths"));
        Path actual = fromStoredPath(repo, paths.get("actualSemantic"));
        if (!Files.isRegularFile(actual)) {
            throw new IOException("Cannot approve missing semantic snapshot: " + actual);
        }
        Path baselineSnapshot = baselineDir.resolve("semantic.snapshot.json");
        Files.copy(actual, baselineSnapshot, StandardCopyOption.REPLACE_EXISTING);

        Path currentImages = item.directory().resolve("render/current");
        Path referenceImages = baselineDir.resolve("reference");
        if (Files.isDirectory(currentImages)) {
            copyPngTree(currentImages, referenceImages);
        }

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("schemaVersion", BASELINE_SCHEMA);
        baseline.put("caseId", item.caseId());
        baseline.put("approvedFromRun", manifest.get("runId"));
        baseline.put("artifactFingerprint", manifest.get("artifactFingerprint"));
        baseline.put("inputHashes", manifest.get("hashes"));
        baseline.put("metrics", manifest.get("metrics"));
        Map<String, String> outputHashes = new LinkedHashMap<>();
        putHash(outputHashes, "semanticSnapshot", baselineSnapshot);
        putHash(outputHashes, "referenceImages", referenceImages);
        baseline.put("outputHashes", outputHashes);
        Path baselineManifest = baselineDir.resolve("baseline.manifest.json");
        GoldenJson.writeValue(baselineManifest, baseline);

        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("schemaVersion", BASELINE_SCHEMA);
        approval.put("caseId", item.caseId());
        approval.put("reviewer", reviewer);
        approval.put("approvedAt", Instant.now().toString());
        approval.put("reason", reason);
        approval.put("artifactFingerprint", manifest.get("artifactFingerprint"));
        approval.put("baselineManifestSha256", hashArtifact(baselineManifest));
        GoldenJson.writeValue(baselineDir.resolve("approval.json"), approval);
    }

    private static void report(Map<String, String> options) throws Exception {
        Path runs = requiredPath(options, "--runs");
        Path output = requiredPath(options, "--output");
        List<ReviewItem> items = filterRun(loadItems(runs), options.getOrDefault("--run-id", "latest"));
        Files.createDirectories(output);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReviewItem item : items) {
            String status = String.valueOf(item.review().get("overallStatus"));
            counts.merge(status, 1, Integer::sum);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("counts", counts);
        report.put("items", items.stream().map(ReviewItem::summary).toList());
        GoldenJson.writeValue(output.resolve("report.json"), report);
        GoldenJson.writeValue(output.resolve("blender-manifest.json"), blenderManifest(items));
        Files.writeString(output.resolve("index.html"), htmlReport(items, counts), StandardCharsets.UTF_8);
        System.out.println("Golden review report: " + output.resolve("index.html").toAbsolutePath());
    }

    private static void verifyRelease(Map<String, String> options) throws Exception {
        Path runs = requiredPath(options, "--runs");
        String runId = options.get("--run-id");
        boolean allowWaivers = Boolean.parseBoolean(options.getOrDefault("--allow-waivers", "false"));
        List<String> failures = new ArrayList<>();
        for (ReviewItem item : loadItems(runs)) {
            if (runId != null && !runId.equals(String.valueOf(item.manifest().get("runId")))) {
                continue;
            }
            String overall = String.valueOf(item.review().get("overallStatus"));
            if (!"passed".equals(overall)
                    && !(allowWaivers && "passed_with_waiver".equals(overall))) {
                failures.add(item.caseId() + "=" + overall);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Golden release gate failed:\n  " + String.join("\n  ", failures));
        }
        System.out.println("Golden release review gate passed.");
    }

    private static void verifyDeterminism(Map<String, String> options) throws Exception {
        Path runs = requiredPath(options, "--runs");
        String runId = required(options, "--run-id");
        int expectedRepeats = Integer.parseInt(options.getOrDefault("--repeats", "3"));
        Map<String, List<ReviewItem>> groups = new LinkedHashMap<>();
        for (ReviewItem item : loadItems(runs)) {
            if (runId.equals(String.valueOf(item.manifest().get("runId")))) {
                groups.computeIfAbsent(item.caseId(), ignored -> new ArrayList<>()).add(item);
            }
        }
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<ReviewItem>> entry : groups.entrySet()) {
            List<ReviewItem> repeated = entry.getValue().stream()
                    .filter(item -> ((Number) item.manifest().getOrDefault("repeat", 1)).intValue() <= expectedRepeats)
                    .toList();
            if (repeated.size() < expectedRepeats) {
                continue;
            }
            checked++;
            Set<String> snapshots = new LinkedHashSet<>();
            for (ReviewItem item : repeated) {
                Map<String, Object> hashes = objectMap(item.manifest().get("hashes"));
                snapshots.add(String.valueOf(hashes.get("semanticSnapshot")));
            }
            if (snapshots.size() != 1) {
                failures.add(entry.getKey() + " produced " + snapshots.size() + " semantic hashes");
            }
        }
        if (checked == 0) {
            throw new AssertionError(
                    "No test case has " + expectedRepeats + " repeats for runId " + runId);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Golden determinism gate failed:\n  " + String.join("\n  ", failures));
        }
        System.out.println("Golden determinism gate passed for runId " + runId + ".");
    }

    private static List<Map<String, Object>> blenderManifest(List<ReviewItem> items) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReviewItem item : items) {
            Map<String, Object> paths = objectMap(item.manifest().get("paths"));
            Object gltf = paths.get("gltf");
            if (gltf == null || String.valueOf(gltf).isBlank()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", item.caseId());
            entry.put("gltf", gltf);
            entry.put("outputDirectory", item.directory().resolve("render").toString());
            Object expected = paths.get("expectedSemantic");
            Path reference = expected == null || String.valueOf(expected).isBlank()
                    ? item.directory().resolve("baseline-reference")
                    : Path.of(String.valueOf(expected)).toAbsolutePath().normalize()
                            .getParent().resolve("reference");
            entry.put("referenceDirectory", reference.toString());
            Object scenarioPath = paths.get("scenarioManifest");
            List<Object> cameras = List.of(Map.of("id", "overview"));
            if (scenarioPath != null && !String.valueOf(scenarioPath).isBlank()) {
                Path path = Path.of(String.valueOf(scenarioPath));
                if (Files.isRegularFile(path)) {
                    JsonNode cameraNode = GoldenJson.mapper().readTree(path.toFile()).path("cameras");
                    if (cameraNode.isArray() && !cameraNode.isEmpty()) {
                        cameras = GoldenJson.mapper().convertValue(cameraNode, new TypeReference<>() {});
                    }
                }
            }
            entry.put("cameras", cameras);
            result.add(entry);
        }
        return result;
    }

    private static String htmlReport(List<ReviewItem> items, Map<String, Integer> counts) {
        StringBuilder rows = new StringBuilder();
        for (ReviewItem item : items) {
            Map<String, Object> review = item.review();
            String overall = String.valueOf(review.get("overallStatus"));
            Map<String, Object> paths = objectMap(item.manifest().get("paths"));
            rows.append("<tr class=\"").append(html(overall)).append("\"><td><code>")
                    .append(html(item.caseId())).append("</code></td><td>")
                    .append(html(String.valueOf(review.get("machineStatus"))))
                    .append("</td><td>").append(html(String.valueOf(review.get("reviewStatus"))))
                    .append("</td><td>").append(html(overall)).append("</td><td>");
            link(rows, paths.get("gltf"), "glTF");
            rows.append(" ");
            link(rows, item.directory().resolve("actual.snapshot.json").toString(), "snapshot");
            rows.append(" ");
            link(rows, paths.get("semanticDiff"), "semantic diff");
            rows.append(" ");
            link(rows, item.directory().resolve("render/current").toString(), "render");
            rows.append("</td><td>").append(html(String.valueOf(review.get("machineDetail"))))
                    .append(" ").append(html(String.valueOf(review.getOrDefault("warnings", List.of()))))
                    .append("</td></tr>\n");
        }
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>VoxelBridge Golden Review</title>
                <style>body{font:14px system-ui;margin:2rem;background:#111;color:#ddd}table{border-collapse:collapse;width:100%%}th,td{padding:.55rem;border:1px solid #444;text-align:left}code{color:#cbe3ff}.passed{background:#15351f}.failed{background:#491d1d}.pending_review{background:#4a401b}.passed_with_waiver{background:#49331a}a{color:#80bdff}.summary{margin-bottom:1rem}</style>
                </head><body><h1>VoxelBridge Golden Review</h1><div class="summary">%s</div>
                <p>This report is read-only. Use approveGolden, rejectGolden, or waiveGolden to record a decision.</p>
                <table><thead><tr><th>Test case</th><th>Machine</th><th>Review</th><th>Overall</th><th>Evidence</th><th>Detail</th></tr></thead><tbody>%s</tbody></table>
                </body></html>
                """.formatted(html(counts.toString()), rows);
    }

    private static void link(StringBuilder target, Object path, String label) {
        if (path == null || String.valueOf(path).isBlank()) {
            target.append("<span>-</span>");
            return;
        }
        URI uri = Path.of(String.valueOf(path)).toAbsolutePath().normalize().toUri();
        target.append("<a href=\"").append(html(uri.toString())).append("\">")
                .append(html(label)).append("</a>");
    }

    private static List<ReviewItem> loadItems(Path runs) throws IOException {
        if (!Files.isDirectory(runs)) {
            return List.of();
        }
        List<ReviewItem> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(runs)) {
            for (Path reviewPath : stream
                    .filter(path -> path.getFileName().toString().equals("review.json"))
                    .sorted()
                    .toList()) {
                Path directory = reviewPath.getParent();
                Path manifestPath = directory.resolve("run-manifest.json");
                if (!Files.isRegularFile(manifestPath)) {
                    continue;
                }
                Map<String, Object> review = GoldenJson.readMap(reviewPath);
                Map<String, Object> manifest = GoldenJson.readMap(manifestPath);
                result.add(new ReviewItem(directory, manifest, review));
            }
        }
        result.sort(Comparator.comparing(ReviewItem::caseId));
        return result;
    }

    private static List<ReviewItem> selectItems(List<ReviewItem> items, String selector) {
        if (selector.equals("all-passed")) {
            return items.stream()
                    .filter(item -> "passed".equals(item.review().get("machineStatus")))
                    .filter(item -> !"approved".equals(item.review().get("reviewStatus")))
                    .toList();
        }
        List<Pattern> patterns = splitCsv(selector).stream().map(GoldenReviewCli::glob).toList();
        return items.stream()
                .filter(item -> patterns.stream().anyMatch(p -> p.matcher(item.caseId()).matches()))
                .toList();
    }

    private static List<ReviewItem> filterRun(List<ReviewItem> items, String requested) {
        if (requested == null || requested.isBlank() || requested.equals("all")) {
            return items;
        }
        String runId = requested;
        if (requested.equals("latest")) {
            runId = items.stream()
                    .map(item -> String.valueOf(item.manifest().get("runId")))
                    .max(String::compareTo)
                    .orElse("");
        }
        String selectedRun = runId;
        return items.stream()
                .filter(item -> selectedRun.equals(String.valueOf(item.manifest().get("runId"))))
                .toList();
    }

    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '[', ']', '$', '^', '{', '}', '|', '+', '\\' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static Map<String, Object> pathMap(
            Path repo, Path runDir, Path jar, Path world, Path scenarioManifest,
            Path scenarioFile, Path actual, Path expected, Path gltf, Path semanticDiff) {
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("runDirectory", runDir.toString());
        paths.put("productionJar", stringPath(jar));
        paths.put("world", stringPath(world));
        paths.put("scenarioManifest", stringPath(scenarioManifest));
        paths.put("scenarioCommands", stringPath(scenarioFile));
        paths.put("actualSemantic", stringPath(actual));
        paths.put("expectedSemantic", stringPath(expected));
        paths.put("semanticDiff", stringPath(semanticDiff));
        paths.put("gltf", stringPath(gltf));
        paths.put("repository", repo.toString());
        return paths;
    }

    private static String stringPath(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }

    private static Path fromStoredPath(Path repo, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return repo.resolve("__missing__");
        }
        Path path = Path.of(String.valueOf(value));
        return path.isAbsolute() ? path.normalize() : repo.resolve(path).normalize();
    }

    private static Path baselineDir(Path repo, String target, String scenario, String variant) {
        return repo.resolve("golden/baselines").resolve(target).resolve(scenario).resolve(variant);
    }

    private static String overallStatus(String machine, String review) {
        if (machine.equals("failed") || machine.equals("error") || review.equals("rejected")) {
            return "failed";
        }
        if (review.equals("waived")) {
            return "passed_with_waiver";
        }
        if (machine.equals("passed") && (review.equals("approved") || review.equals("not_required"))) {
            return "passed";
        }
        return "pending_review";
    }

    private static boolean jsonEquals(Path first, Path second) throws IOException {
        JsonNode a = GoldenJson.mapper().readTree(first.toFile());
        JsonNode b = GoldenJson.mapper().readTree(second.toFile());
        return a.equals(b);
    }

    private static ProbeCheck verifyProbes(Path manifestPath, Path jsonl) throws IOException {
        JsonNode manifest = GoldenJson.mapper().readTree(manifestPath.toFile());
        Set<String> required = textSet(manifest.path("requiredProbes"));
        Set<String> forbidden = textSet(manifest.path("forbiddenProbes"));
        if (required.isEmpty() && forbidden.isEmpty()) {
            return new ProbeCheck(true, "probe policy not configured");
        }
        if (!Files.isRegularFile(jsonl)) {
            return new ProbeCheck(false, "required structured probe output is missing: " + jsonl);
        }
        Set<String> observed = new LinkedHashSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode event = GoldenJson.mapper().readTree(line);
                String name = event.path("event").asText("");
                if (!name.isBlank()) {
                    observed.add(name);
                }
            } catch (Exception malformed) {
                return new ProbeCheck(false, "malformed probe.jsonl line " + lineNumber);
            }
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(observed);
        if (!missing.isEmpty()) {
            return new ProbeCheck(false, "required probes were not observed: " + missing);
        }
        Set<String> prohibited = new LinkedHashSet<>(forbidden);
        prohibited.retainAll(observed);
        if (!prohibited.isEmpty()) {
            return new ProbeCheck(false, "forbidden probes were observed: " + prohibited);
        }
        return new ProbeCheck(true, "probe policy passed");
    }

    private static List<String> performanceWarnings(
            Path scenarioManifest, Path baselineManifest, Map<String, Object> currentMetrics)
            throws IOException {
        if (scenarioManifest == null || !Files.isRegularFile(scenarioManifest)
                || !Files.isRegularFile(baselineManifest)) {
            return List.of();
        }
        JsonNode scenario = GoldenJson.mapper().readTree(scenarioManifest.toFile());
        double warningPercent = scenario.path("performanceBudget")
                .path("warningRegressionPercent").asDouble(20.0);
        Map<String, Object> baseline = GoldenJson.readMap(baselineManifest);
        Map<String, Object> baselineMetrics = objectMap(baseline.get("metrics"));
        double previous = number(baselineMetrics.get("durationMillis"));
        double current = number(currentMetrics.get("durationMillis"));
        if (previous > 0 && current > previous * (1.0 + warningPercent / 100.0)) {
            return List.of("duration regressed by "
                    + Math.round((current / previous - 1.0) * 100.0)
                    + "% (warning threshold " + warningPercent + "%)");
        }
        return List.of();
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Set<String> textSet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    result.add(value.asText());
                }
            });
        }
        return result;
    }

    private static void putHash(Map<String, String> target, String name, Path path) throws IOException {
        target.put(name, path == null || !Files.exists(path) ? "" : hashArtifact(path));
    }

    static String hashArtifact(Path path) throws IOException {
        MessageDigest digest = sha256();
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            digest.update(Files.readAllBytes(normalized));
            return HexFormat.of().formatHex(digest.digest());
        }
        if (!Files.isDirectory(normalized)) {
            return "";
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(normalized)) {
            files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> normalized.relativize(file).toString()))
                    .toList();
        }
        for (Path file : files) {
            digest.update(normalized.relativize(file).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashStrings(Map<String, String> values) {
        MessageDigest digest = sha256();
        values.forEach((key, value) -> {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '=');
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyPngTree(Path source, Path destination) throws IOException {
        if (Files.exists(destination)) {
            Files.walkFileTree(destination, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (!dir.equals(destination)) {
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(destination);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .toList()) {
                Path relative = source.relativize(file);
                Path target = destination.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String detectReviewer(Path repo) {
        try {
            Process process = new ProcessBuilder("git", "config", "user.name")
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !value.isBlank()) {
                return value;
            }
        } catch (Exception ignored) {
            // Fall through to the OS identity.
        }
        return System.getProperty("user.name", "unknown");
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return GoldenJson.mapper().convertValue(value, new TypeReference<>() {});
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (index + 1 >= args.length || !args[index].startsWith("--")) {
                throw new IllegalArgumentException("Expected --name value near argument " + index);
            }
            result.put(args[index], args[index + 1]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option " + name);
        }
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        return Path.of(required(options, name)).toAbsolutePath().normalize();
    }

    private static Path optionalPath(Map<String, String> options, String name) {
        String value = options.get(name);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path path(Map<String, String> options, String name, Path fallback) {
        Path value = optionalPath(options, name);
        return value == null ? fallback.toAbsolutePath().normalize() : value;
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void usage() {
        System.err.println("GoldenReviewCli record|diff|report|approve|reject|waive|verify-release [options]");
    }

    private record ReviewItem(
            Path directory, Map<String, Object> manifest, Map<String, Object> review) {
        String caseId() {
            return String.valueOf(manifest.get("caseId"));
        }

        Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", caseId());
            result.put("directory", directory.toString());
            result.put("machineStatus", review.get("machineStatus"));
            result.put("reviewStatus", review.get("reviewStatus"));
            result.put("overallStatus", review.get("overallStatus"));
            result.put("artifactFingerprint", review.get("artifactFingerprint"));
            return result;
        }
    }

    private record ProbeCheck(boolean passed, String detail) {}
}
