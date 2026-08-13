package com.voxelbridge.verification;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public final class SemanticGltfAnalyzerTest {
    private final Path tempDir;

    private SemanticGltfAnalyzerTest(Path tempDir) {
        this.tempDir = tempDir;
    }

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("voxelbridge-golden-self-test-");
        SemanticGltfAnalyzerTest tests = new SemanticGltfAnalyzerTest(tempDir);
        tests.triangleArrivalOrderDoesNotChangeSnapshot();
        tests.windingChangeChangesGeometryHash();
        tests.invalidIndexFailsVerification();
        tests.nonFiniteUvIsTreatedAsMissingCoordinates();
        tests.semanticAssertionsReportTargetedCounts();
        tests.semanticAssertionFailureNamesTheFeature();
        tests.worldOriginFaceAssertionsDoNotApplyCenterOffset();
        tests.semanticAttributeAssertionsAcceptTintedAtlasGeometry();
        tests.semanticAttributeAssertionsRejectBlackTintAndRawUvs();
        tests.appearanceHashIgnoresAtlasPlacementButTracksTint();
        tests.markerOnlyEmissiveMaterialHasNoStandardEmission();
        tests.standardEmissionOnMarkerOnlyMaterialFailsVerification();
        System.out.println("Semantic glTF verifier self-tests passed.");
    }

    void triangleArrivalOrderDoesNotChangeSnapshot() throws Exception {
        Path scenario = tempDir.resolve("scenario.mcfunction");
        Files.writeString(scenario, "setblock 0 64 0 minecraft:stone\n");

        Path first = writeFixture(tempDir.resolve("first"), new int[]{0, 1, 2, 0, 2, 3});
        Path second = writeFixture(tempDir.resolve("second"), new int[]{0, 2, 3, 0, 1, 2});

        GoldenSnapshot firstSnapshot = SemanticGltfAnalyzer.analyze(
                first, "quad", "test", scenario, 1.0e-5);
        GoldenSnapshot secondSnapshot = SemanticGltfAnalyzer.analyze(
                second, "quad", "test", scenario, 1.0e-5);

        requireEquals(firstSnapshot, secondSnapshot, "triangle order changed the snapshot");
        requireEquals(2L, firstSnapshot.triangleCount(), "unexpected triangle count");
        requireEquals(1, firstSnapshot.images().size(), "unexpected image count");
    }

    void windingChangeChangesGeometryHash() throws Exception {
        Path normal = writeFixture(tempDir.resolve("normal"), new int[]{0, 1, 2, 0, 2, 3});
        Path reversed = writeFixture(tempDir.resolve("reversed"), new int[]{0, 2, 1, 0, 3, 2});

        GoldenSnapshot normalSnapshot = SemanticGltfAnalyzer.analyze(
                normal, "quad", "test", null, 1.0e-5);
        GoldenSnapshot reversedSnapshot = SemanticGltfAnalyzer.analyze(
                reversed, "quad", "test", null, 1.0e-5);

        if (normalSnapshot.geometryHash().equals(reversedSnapshot.geometryHash())) {
            throw new AssertionError("winding change did not change the geometry hash");
        }
    }

    void invalidIndexFailsVerification() throws Exception {
        Path invalid = writeFixture(tempDir.resolve("invalid"), new int[]{0, 1, 9});

        try {
            SemanticGltfAnalyzer.analyze(invalid, "invalid", "test", null, 1.0e-5);
            throw new AssertionError("invalid index was accepted");
        } catch (IOException expected) {
            // Expected strict validation failure.
        }
    }

    void nonFiniteUvIsTreatedAsMissingCoordinates() throws Exception {
        Path invalid = writeAttributeFixture(
                tempDir.resolve("non-finite-uv"),
                "entity:minecraft:boat",
                new float[]{
                        Float.NaN, 0.0f,
                        1.0f, 0.0f,
                        1.0f, 1.0f,
                        0.0f, 1.0f
                },
                new float[]{
                        1.0f, 1.0f, 1.0f, 1.0f,
                        1.0f, 1.0f, 1.0f, 1.0f,
                        1.0f, 1.0f, 1.0f, 1.0f,
                        1.0f, 1.0f, 1.0f, 1.0f
                });

        SemanticGltfAnalyzer.analyze(invalid, "missing-uv", "test", null, 1.0e-5);
    }

    void semanticAssertionsReportTargetedCounts() throws Exception {
        Path fixture = writeFixture(
                tempDir.resolve("semantic-pass"),
                new int[]{0, 1, 2, 0, 2, 3},
                "entity:minecraft/test");
        Path manifest = writeManifest(
                tempDir.resolve("semantic-pass/scenario.json"),
                """
                [
                  {
                    "id": "entity_geometry",
                    "type": "material",
                    "materialRegex": "^entity:",
                    "minMaterials": 1,
                    "expectedTriangles": 2
                  },
                  {
                    "id": "front_face",
                    "type": "face",
                    "materialRegex": "^entity:",
                    "face": {
                      "space": "gltf",
                      "axis": "z",
                      "coordinate": 0,
                      "bounds": {"x": [0, 1], "y": [0, 1]}
                    },
                    "expectedTriangles": 2
                  }
                ]
                """);

        GoldenSnapshot snapshot = SemanticGltfAnalyzer.analyze(
                fixture, "semantic", "test", null, manifest, 1.0e-5);

        requireEquals(2, snapshot.assertions().size(), "unexpected assertion count");
        requireEquals(2L, snapshot.assertions().get(0).triangleCount(),
                "material assertion did not count triangles");
        requireEquals(2L, snapshot.assertions().get(1).triangleCount(),
                "face assertion did not count planar triangles");
    }

    void semanticAssertionFailureNamesTheFeature() throws Exception {
        Path fixture = writeFixture(
                tempDir.resolve("semantic-fail"),
                new int[]{0, 1, 2, 0, 2, 3},
                "minecraft:block/test");
        Path manifest = writeManifest(
                tempDir.resolve("semantic-fail/scenario.json"),
                """
                [
                  {
                    "id": "culled_contact",
                    "type": "face",
                    "materialRegex": "^minecraft:block/test$",
                    "face": {"space": "gltf", "axis": "z", "coordinate": 0},
                    "expectedTriangles": 0
                  }
                ]
                """);

        try {
            SemanticGltfAnalyzer.analyze(
                    fixture, "semantic", "test", null, manifest, 1.0e-5);
            throw new AssertionError("broken culling assertion was accepted");
        } catch (AssertionError expected) {
            if (!expected.getMessage().contains("culled_contact")
                    || !expected.getMessage().contains("actual=2")) {
                throw new AssertionError("assertion failure was not targeted: " + expected.getMessage());
            }
        }
    }

    void worldOriginFaceAssertionsDoNotApplyCenterOffset() throws Exception {
        Path fixture = writeFixture(
                tempDir.resolve("semantic-world-origin"),
                new int[]{0, 1, 2, 0, 2, 3},
                "minecraft:block/test");
        Path manifest = tempDir.resolve("semantic-world-origin/scenario.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion": 1,
                  "selection": {"min": [0, 0, 0], "max": [1, 1, 1]},
                  "export": {"coordinateMode": "world_origin"},
                  "assertions": [{
                    "id": "world_origin_front_face",
                    "type": "face",
                    "materialRegex": "^minecraft:block/test$",
                    "face": {
                      "axis": "z",
                      "coordinate": 0,
                      "bounds": {"x": [0, 1], "y": [0, 1]}
                    },
                    "expectedTriangles": 2
                  }]
                }
                """);

        SemanticGltfAnalyzer.analyze(
                fixture, "world-origin", "test", null, manifest, 1.0e-5);
    }

    void semanticAttributeAssertionsAcceptTintedAtlasGeometry() throws Exception {
        Path fixture = writeAttributeFixture(
                tempDir.resolve("attribute-pass"),
                "minecraft:continuity_overlay",
                new float[]{
                        0.10f, 0.20f,
                        0.20f, 0.20f,
                        0.20f, 0.30f,
                        0.10f, 0.30f
                },
                new float[]{
                        0.20f, 0.80f, 0.10f, 1.0f,
                        0.20f, 0.80f, 0.10f, 1.0f,
                        0.20f, 0.80f, 0.10f, 1.0f,
                        0.20f, 0.80f, 0.10f, 1.0f
                });
        Path manifest = writeManifest(
                tempDir.resolve("attribute-pass/scenario.json"),
                """
                [{
                  "id": "continuity_overlay_tint_and_atlas_uv",
                  "type": "material",
                  "materialRegex": "continuity_overlay",
                  "minColorVertices": 4,
                  "minNonBlackColorVertices": 4,
                  "minNonWhiteColorVertices": 4,
                  "minUvVertices": 4,
                  "maxOutOfRangeUvVertices": 0,
                  "maxFullRangeUvPrimitives": 0,
                  "maxUvSpanU": 0.11,
                  "maxUvSpanV": 0.11
                }]
                """);

        SemanticGltfAnalyzer.analyze(
                fixture, "attributes", "test", null, manifest, 1.0e-5);
    }

    void semanticAttributeAssertionsRejectBlackTintAndRawUvs() throws Exception {
        Path fixture = writeAttributeFixture(
                tempDir.resolve("attribute-fail"),
                "blockentity:minecraft:banner",
                new float[]{
                        0.5654297f, 0.1308594f,
                        0.6162109f, 0.1308594f,
                        0.6162109f, 0.2207031f,
                        0.5654297f, 0.2207031f
                },
                new float[]{
                        0.0f, 0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                });

        Path tintManifest = writeManifest(
                tempDir.resolve("attribute-fail/tint-scenario.json"),
                """
                [{
                  "id": "block_entity_not_black",
                  "type": "material",
                  "materialRegex": "^blockentity:",
                  "minNonBlackColorVertices": 1
                }]
                """);
        requireAssertionFailure(
                fixture, tintManifest, "block_entity_not_black", "actual=0");

        Path uvManifest = writeManifest(
                tempDir.resolve("attribute-fail/uv-scenario.json"),
                """
                [{
                  "id": "atlas_uv_was_remapped",
                  "type": "material",
                  "materialRegex": "^blockentity:",
                  "maxUvSpanU": 0.02,
                  "maxUvSpanV": 0.02
                }]
                """);
        requireAssertionFailure(
                fixture, uvManifest, "atlas_uv_was_remapped", "maxUvSpanU=0.02");
    }

    void appearanceHashIgnoresAtlasPlacementButTracksTint() throws Exception {
        float[] white = {
                1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1
        };
        float[] red = {
                1, 0, 0, 1, 1, 0, 0, 1,
                1, 0, 0, 1, 1, 0, 0, 1
        };
        Path individual = writeAttributeFixture(
                tempDir.resolve("appearance-individual"), "minecraft:block/test",
                new float[]{0, 0, 1, 0, 1, 1, 0, 1}, white);
        Path atlas = writeAttributeFixture(
                tempDir.resolve("appearance-atlas"), "minecraft:block/test",
                new float[]{.25f, .25f, .50f, .25f, .50f, .50f, .25f, .50f}, white);
        Path tinted = writeAttributeFixture(
                tempDir.resolve("appearance-tinted"), "minecraft:block/test",
                new float[]{.25f, .25f, .50f, .25f, .50f, .50f, .25f, .50f}, red);

        GoldenSnapshot individualSnapshot = SemanticGltfAnalyzer.analyze(
                individual, "appearance", "test", null, 1.0e-5);
        GoldenSnapshot atlasSnapshot = SemanticGltfAnalyzer.analyze(
                atlas, "appearance", "test", null, 1.0e-5);
        GoldenSnapshot tintedSnapshot = SemanticGltfAnalyzer.analyze(
                tinted, "appearance", "test", null, 1.0e-5);
        requireEquals(individualSnapshot.appearanceHash(), atlasSnapshot.appearanceHash(),
                "atlas placement changed the appearance hash");
        if (individualSnapshot.geometryHash().equals(atlasSnapshot.geometryHash())) {
            throw new AssertionError("raw UV placement was absent from geometry hash");
        }
        if (atlasSnapshot.appearanceHash().equals(tintedSnapshot.appearanceHash())) {
            throw new AssertionError("vertex tint did not change the appearance hash");
        }
    }

    void markerOnlyEmissiveMaterialHasNoStandardEmission() throws Exception {
        Path fixture = writeFixture(
                tempDir.resolve("marker-only-emissive"),
                new int[]{0, 1, 2, 0, 2, 3});
        addEmissiveMarker(fixture, false);

        SemanticGltfAnalyzer.analyze(
                fixture, "marker-only-emissive", "test", null, 1.0e-5);
    }

    void standardEmissionOnMarkerOnlyMaterialFailsVerification() throws Exception {
        Path fixture = writeFixture(
                tempDir.resolve("invalid-marker-only-emissive"),
                new int[]{0, 1, 2, 0, 2, 3});
        addEmissiveMarker(fixture, true);

        try {
            SemanticGltfAnalyzer.analyze(
                    fixture, "invalid-marker-only-emissive", "test", null, 1.0e-5);
            throw new AssertionError("standard glTF emission was accepted on a marker-only material");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("marker-only emissive")
                    || !expected.getMessage().contains("minecraft:block/test")) {
                throw new AssertionError(
                        "marker-only emission failure was not targeted: " + expected.getMessage());
            }
        }
    }

    private static void addEmissiveMarker(Path gltf, boolean includeStandardEmission)
            throws IOException {
        String json = Files.readString(gltf);
        String materialName = "\"name\": \"minecraft:block/test\",";
        String emission = includeStandardEmission
                ? "\n                    \"emissiveFactor\": [1, 1, 1],"
                : "";
        String replacement = materialName
                + "\n                    \"extras\": {\"voxelbridge:emissive\": true},"
                + emission;
        if (!json.contains(materialName)) {
            throw new IOException("test fixture material was not found");
        }
        Files.writeString(gltf, json.replace(materialName, replacement));
    }

    private static void requireAssertionFailure(
            Path fixture,
            Path manifest,
            String assertionId,
            String expectedDetail) throws Exception {
        try {
            SemanticGltfAnalyzer.analyze(
                    fixture, "attributes", "test", null, manifest, 1.0e-5);
            throw new AssertionError("broken attribute assertion was accepted: " + assertionId);
        } catch (AssertionError expected) {
            if (!expected.getMessage().contains(assertionId)
                    || !expected.getMessage().contains(expectedDetail)) {
                throw new AssertionError(
                        "attribute assertion failure was not targeted: " + expected.getMessage());
            }
        }
    }

    private static void requireEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static Path writeFixture(Path directory, int[] indices) throws Exception {
        return writeFixture(directory, indices, "minecraft:block/test");
    }

    private static Path writeFixture(Path directory, int[] indices, String materialName) throws Exception {
        Files.createDirectories(directory);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3366cc);
        ImageIO.write(image, "png", directory.resolve("red.png").toFile());

        float[] positions = {
                0, 0, 0,
                1, 0, 0,
                1, 1, 0,
                0, 1, 0
        };
        ByteBuffer buffer = ByteBuffer.allocate(positions.length * 4 + indices.length * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float position : positions) {
            buffer.putFloat(position);
        }
        for (int index : indices) {
            buffer.putShort((short) index);
        }
        String data = Base64.getEncoder().encodeToString(buffer.array());

        String json = """
                {
                  "asset": {"version": "2.0", "generator": "test"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{
                    "attributes": {"POSITION": 0},
                    "indices": 1,
                    "material": 0,
                    "mode": 4
                  }]}],
                  "materials": [{
                    "name": "%s",
                    "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}
                  }],
                  "textures": [{"source": 0}],
                  "images": [{"uri": "red.png"}],
                  "buffers": [{
                    "uri": "data:application/octet-stream;base64,%s",
                    "byteLength": %d
                  }],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 48},
                    {"buffer": 0, "byteOffset": 48, "byteLength": %d}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "count": %d, "type": "SCALAR"}
                  ]
                }
                """.formatted(materialName, data, buffer.capacity(), indices.length * 2, indices.length);
        Path gltf = directory.resolve("fixture.gltf");
        Files.writeString(gltf, json);
        return gltf;
    }

    private static Path writeAttributeFixture(
            Path directory,
            String materialName,
            float[] uv0,
            float[] colors) throws Exception {
        if (uv0.length != 8 || colors.length != 16) {
            throw new IllegalArgumentException("attribute fixture requires four UVs and four RGBA colors");
        }
        Files.createDirectories(directory);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff3366cc);
        ImageIO.write(image, "png", directory.resolve("texture.png").toFile());

        float[] positions = {
                0, 0, 0,
                1, 0, 0,
                1, 1, 0,
                0, 1, 0
        };
        int[] indices = {0, 1, 2, 0, 2, 3};
        int positionsBytes = positions.length * Float.BYTES;
        int uvBytes = uv0.length * Float.BYTES;
        int colorBytes = colors.length * Float.BYTES;
        int indexBytes = indices.length * Short.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(
                        positionsBytes + uvBytes + colorBytes + indexBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : positions) {
            buffer.putFloat(value);
        }
        for (float value : uv0) {
            buffer.putFloat(value);
        }
        for (float value : colors) {
            buffer.putFloat(value);
        }
        for (int index : indices) {
            buffer.putShort((short) index);
        }
        String data = Base64.getEncoder().encodeToString(buffer.array());
        int uvOffset = positionsBytes;
        int colorOffset = uvOffset + uvBytes;
        int indexOffset = colorOffset + colorBytes;

        String json = """
                {
                  "asset": {"version": "2.0", "generator": "test"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{
                    "attributes": {
                      "POSITION": 0,
                      "TEXCOORD_0": 1,
                      "COLOR_0": 2
                    },
                    "indices": 3,
                    "material": 0,
                    "mode": 4
                  }]}],
                  "materials": [{
                    "name": "%s",
                    "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}
                  }],
                  "textures": [{"source": 0}],
                  "images": [{"uri": "texture.png"}],
                  "buffers": [{
                    "uri": "data:application/octet-stream;base64,%s",
                    "byteLength": %d
                  }],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": %d},
                    {"buffer": 0, "byteOffset": %d, "byteLength": %d},
                    {"buffer": 0, "byteOffset": %d, "byteLength": %d},
                    {"buffer": 0, "byteOffset": %d, "byteLength": %d}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC2"},
                    {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC4"},
                    {"bufferView": 3, "componentType": 5123, "count": 6, "type": "SCALAR"}
                  ]
                }
                """.formatted(
                materialName,
                data,
                buffer.capacity(),
                positionsBytes,
                uvOffset,
                uvBytes,
                colorOffset,
                colorBytes,
                indexOffset,
                indexBytes);
        Path gltf = directory.resolve("fixture.gltf");
        Files.writeString(gltf, json);
        return gltf;
    }

    private static Path writeManifest(Path path, String assertions) throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "selection": {"min": [0, 0, 0], "max": [1, 1, 1]},
                  "assertions": %s
                }
                """.formatted(assertions);
        Files.writeString(path, json);
        return path;
    }
}
