package com.voxelbridge.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class GoldenReviewCliTest {
    private GoldenReviewCliTest() {}

    public static void main(String[] args) throws Exception {
        Path repo = Files.createTempDirectory("voxelbridge-review-test-");
        Path run = repo.resolve("build/golden-runs/run-1/fabric-1.21.11/banner_atlas/atlas_on");
        Files.createDirectories(run);
        Path jar = repo.resolve("mod.jar");
        Path world = repo.resolve("world.zip");
        Path scenario = repo.resolve("scenario.json");
        Path commands = repo.resolve("scene.mcfunction");
        Path gltfDir = run.resolve("export/gltf");
        Files.createDirectories(gltfDir);
        Path gltf = gltfDir.resolve("scene.gltf");
        Files.writeString(jar, "jar-v1");
        Files.writeString(world, "world-v1");
        Files.writeString(scenario, "{\"schemaVersion\":2}");
        Files.writeString(commands, "setblock 0 0 0 stone");
        Files.writeString(gltf, "{\"asset\":{\"version\":\"2.0\"}}");
        Files.writeString(run.resolve("actual.snapshot.json"), "{\"value\":1}");
        Files.writeString(run.resolve("result.json"), """
                {"status":"passed","gltf":"%s","error":"","jarSha256":"unused"}
                """.formatted(gltf.toString().replace("\\", "\\\\")));

        record(repo, run, jar, world, scenario, commands);
        Map<String, Object> pending = GoldenJson.readMap(run.resolve("review.json"));
        requireEquals("passed", pending.get("machineStatus"), "new baseline machine status");
        requireEquals("pending", pending.get("reviewStatus"), "new baseline review status");

        GoldenReviewCli.main(new String[]{
                "approve", "--repo", repo.toString(), "--runs", repo.resolve("build/golden-runs").toString(),
                "--selector", "fabric-1.21.11/banner_atlas/atlas_on",
                "--reason", "initial reviewed fixture", "--reviewer", "Golden Test",
                "--confirm", "true"
        });
        Path baseline = repo.resolve(
                "golden/baselines/fabric-1.21.11/banner_atlas/atlas_on/semantic.snapshot.json");
        if (!Files.isRegularFile(baseline)) {
            throw new AssertionError("approval did not create a semantic baseline");
        }

        record(repo, run, jar, world, scenario, commands);
        Map<String, Object> inherited = GoldenJson.readMap(run.resolve("review.json"));
        requireEquals("approved", inherited.get("reviewStatus"), "approval was not inherited");
        requireEquals("passed", inherited.get("overallStatus"), "approved run did not pass");

        Files.writeString(jar, "jar-v2");
        record(repo, run, jar, world, scenario, commands);
        Map<String, Object> stale = GoldenJson.readMap(run.resolve("review.json"));
        requireEquals("stale", stale.get("reviewStatus"), "changed jar did not stale approval");
        requireEquals("pending_review", stale.get("overallStatus"), "stale approval passed unexpectedly");

        GoldenReviewCli.main(new String[]{
                "reject", "--repo", repo.toString(), "--runs", repo.resolve("build/golden-runs").toString(),
                "--selector", "fabric-1.21.11/banner_atlas/*", "--category", "uv",
                "--reason", "atlas samples are shifted", "--reviewer", "Golden Test",
                "--confirm", "true"
        });
        Map<String, Object> rejected = GoldenJson.readMap(run.resolve("review.json"));
        requireEquals("rejected", rejected.get("reviewStatus"), "rejection was not recorded");
        requireEquals("failed", rejected.get("overallStatus"), "rejection did not fail the item");

        Path report = repo.resolve("build/reports/golden");
        GoldenReviewCli.main(new String[]{
                "report", "--runs", repo.resolve("build/golden-runs").toString(),
                "--output", report.toString()
        });
        if (!Files.isRegularFile(report.resolve("index.html"))
                || !Files.isRegularFile(report.resolve("blender-manifest.json"))) {
            throw new AssertionError("review report was not generated");
        }
        System.out.println("Golden review workflow self-tests passed.");
    }

    private static void record(
            Path repo, Path run, Path jar, Path world, Path scenario, Path commands) throws Exception {
        GoldenReviewCli.main(new String[]{
                "record", "--repo", repo.toString(), "--run-dir", run.toString(),
                "--run-id", "run-1", "--target", "fabric-1.21.11", "--platform", "fabric",
                "--minecraft", "1.21.11", "--scenario", "banner_atlas", "--variant", "atlas_on",
                "--result", run.resolve("result.json").toString(),
                "--actual", run.resolve("actual.snapshot.json").toString(),
                "--jar", jar.toString(), "--world", world.toString(),
                "--scenario-manifest", scenario.toString(), "--scenario-file", commands.toString(),
                "--gltf", run.resolve("export/gltf/scene.gltf").toString(),
                "--git-commit", "test-commit", "--tool-version", "test-tool"
        });
    }

    private static void requireEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
