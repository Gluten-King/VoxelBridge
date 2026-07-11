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

    private static void requireEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static Path writeFixture(Path directory, int[] indices) throws Exception {
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
                    "name": "minecraft:block/test",
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
                """.formatted(data, buffer.capacity(), indices.length * 2, indices.length);
        Path gltf = directory.resolve("fixture.gltf");
        Files.writeString(gltf, json);
        return gltf;
    }
}
