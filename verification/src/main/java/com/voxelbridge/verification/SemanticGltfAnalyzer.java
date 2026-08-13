package com.voxelbridge.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voxelbridge.verification.GoldenSnapshot.AssertionSnapshot;
import com.voxelbridge.verification.GoldenSnapshot.ImageSnapshot;
import com.voxelbridge.verification.GoldenSnapshot.MaterialSnapshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SemanticGltfAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SNAPSHOT_SCHEMA_VERSION = 3;

    private SemanticGltfAnalyzer() {}

    public static GoldenSnapshot analyze(
            Path gltfPath,
            String scenario,
            String minecraftVersion,
            Path scenarioFile,
            double epsilon) throws IOException {
        return analyze(gltfPath, scenario, minecraftVersion, scenarioFile, null, epsilon);
    }

    public static GoldenSnapshot analyze(
            Path gltfPath,
            String scenario,
            String minecraftVersion,
            Path scenarioFile,
            Path scenarioManifest,
            double epsilon) throws IOException {
        if (!(epsilon > 0.0) || !Double.isFinite(epsilon)) {
            throw new IllegalArgumentException("epsilon must be finite and greater than zero");
        }
        if (!Files.isRegularFile(gltfPath)) {
            throw new IOException("glTF file does not exist: " + gltfPath);
        }

        JsonNode root = JSON.readTree(gltfPath.toFile());
        if (!"2.0".equals(root.path("asset").path("version").asText())) {
            throw new IOException("Only glTF 2.0 assets are supported: " + gltfPath);
        }

        Path baseDir = gltfPath.toAbsolutePath().normalize().getParent();
        Accessors accessors = new Accessors(root, baseDir);
        List<ImageInfo> imageInfos = readImages(root, accessors, baseDir);
        List<ImageSnapshot> imageSnapshots = imageInfos.stream()
                .map(ImageInfo::snapshot)
                .sorted(Comparator.comparing(ImageSnapshot::id)
                        .thenComparing(ImageSnapshot::rgbaHash))
                .distinct()
                .toList();
        validateVoxelBridgeLightmap(root, imageInfos);
        validateMarkerOnlyEmissiveMaterials(root);

        JsonNode meshes = array(root, "meshes");
        boolean voxelBridgeSceneContract = containsText(
                array(root, "extensionsUsed"), "VOXELBRIDGE_minecraft_scene");
        if (requiresSceneContract(scenarioManifest) && !voxelBridgeSceneContract) {
            throw new IOException("Scenario requires VOXELBRIDGE_minecraft_scene, but the glTF does not declare it");
        }
        JsonNode voxelBridgeScene = root.path("extensions").path("VOXELBRIDGE_minecraft_scene");
        int voxelBridgeSceneVersion = voxelBridgeScene.path("version").asInt(1);
        boolean standardSemanticTexCoords =
                voxelBridgeSceneContract && voxelBridgeSceneVersion >= 2;
        if (voxelBridgeSceneContract && standardSemanticTexCoords) {
            validateSemanticTexCoordContract(voxelBridgeScene);
        }
        Map<String, MaterialAccumulator> materialData = new TreeMap<>();
        List<TriangleGeometry> triangleGeometry = new ArrayList<>();
        List<PrimitiveAttributeStats> primitiveAttributeStats = new ArrayList<>();
        long totalVertices = 0;
        long totalTriangles = 0;
        int primitiveCount = 0;
        double[] boundsMin = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] boundsMax = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};

        for (JsonNode mesh : meshes) {
            for (JsonNode primitive : array(mesh, "primitives")) {
                int mode = primitive.path("mode").asInt(4);
                if (mode != 4) {
                    throw new IOException("Golden verification currently requires TRIANGLES primitives; mode=" + mode);
                }

                JsonNode attributes = primitive.path("attributes");
                if (!attributes.isObject() || !attributes.has("POSITION")) {
                    throw new IOException("Mesh primitive is missing POSITION");
                }

                AccessorData positions = accessors.read(attributes.path("POSITION").asInt(-1));
                if (positions.components() != 3) {
                    throw new IOException("POSITION accessor must use VEC3");
                }
                AccessorData normals = optionalAccessor(accessors, attributes, "NORMAL");
                AccessorData tangents = optionalAccessor(accessors, attributes, "TANGENT");
                AccessorData uv0 = optionalUvAccessor(accessors, attributes, "TEXCOORD_0");
                AccessorData uv1 = optionalUvAccessor(accessors, attributes, "TEXCOORD_1");
                AccessorData uv2 = optionalUvAccessor(accessors, attributes, "TEXCOORD_2");
                AccessorData uv3 = optionalUvAccessor(accessors, attributes, "TEXCOORD_3");
                AccessorData uv4 = optionalUvAccessor(accessors, attributes, "TEXCOORD_4");
                AccessorData colors = optionalAccessor(accessors, attributes, "COLOR_0");
                AccessorData lightUv = standardSemanticTexCoords
                        ? uv2
                        : optionalAccessor(
                                accessors, attributes, "_VOXELBRIDGE_LIGHT_UV");
                AccessorData midTexCoord = standardSemanticTexCoords
                        ? uv3
                        : optionalAccessor(
                                accessors, attributes, "_VOXELBRIDGE_MID_TEX_COORD");
                AccessorData midBlock = optionalAccessor(
                        accessors, attributes, "_VOXELBRIDGE_MID_BLOCK");
                AccessorData materialIdentity = standardSemanticTexCoords
                        ? uv4
                        : optionalAccessor(
                                accessors, attributes, "_VOXELBRIDGE_MATERIAL_ID");
                validateAttributeCount(positions, normals, "NORMAL");
                validateAttributeCount(positions, tangents, "TANGENT");
                validateAttributeCount(positions, uv0, "TEXCOORD_0");
                validateAttributeCount(positions, uv1, "TEXCOORD_1");
                validateAttributeCount(positions, uv2, "TEXCOORD_2");
                validateAttributeCount(positions, uv3, "TEXCOORD_3");
                validateAttributeCount(positions, uv4, "TEXCOORD_4");
                validateAttributeCount(positions, colors, "COLOR_0");
                validateAttributeCount(
                        positions, lightUv,
                        standardSemanticTexCoords
                                ? "TEXCOORD_2"
                                : "_VOXELBRIDGE_LIGHT_UV");
                validateAttributeCount(
                        positions, midTexCoord,
                        standardSemanticTexCoords
                                ? "TEXCOORD_3"
                                : "_VOXELBRIDGE_MID_TEX_COORD");
                validateAttributeCount(positions, midBlock, "_VOXELBRIDGE_MID_BLOCK");
                validateAttributeCount(
                        positions, materialIdentity,
                        standardSemanticTexCoords
                                ? "TEXCOORD_4"
                                : "_VOXELBRIDGE_MATERIAL_ID");
                validateFiniteAccessor(positions, "POSITION");
                validateFiniteAccessor(normals, "NORMAL");
                validateFiniteAccessor(tangents, "TANGENT");
                validateFiniteAccessor(uv0, "TEXCOORD_0");
                validateFiniteAccessor(uv1, "TEXCOORD_1");
                validateFiniteAccessor(uv2, "TEXCOORD_2");
                validateFiniteAccessor(uv3, "TEXCOORD_3");
                validateFiniteAccessor(uv4, "TEXCOORD_4");
                validateFiniteAccessor(colors, "COLOR_0");
                validateFiniteAccessor(midBlock, "_VOXELBRIDGE_MID_BLOCK");
                if (voxelBridgeSceneContract) {
                    if (normals == null) {
                        throw new IOException(
                                "VOXELBRIDGE_minecraft_scene primitive is missing NORMAL");
                    }
                    if (tangents == null || tangents.components() != 4) {
                        throw new IOException(
                                "VOXELBRIDGE_minecraft_scene primitive requires VEC4 TANGENT");
                    }
                    validateTangents(tangents);
                    if (standardSemanticTexCoords
                            && (uv1 == null || uv1.components() != 2)) {
                        throw new IOException(
                                "VOXELBRIDGE_minecraft_scene v2 primitive requires VEC2 "
                                        + "TEXCOORD_1");
                    }
                    if (lightUv == null || lightUv.components() != 2) {
                        throw new IOException(
                                "VOXELBRIDGE_minecraft_scene primitive requires VEC2 "
                                        + (standardSemanticTexCoords
                                                ? "TEXCOORD_2"
                                                : "_VOXELBRIDGE_LIGHT_UV"));
                    }
                    validateLightUv(lightUv, standardSemanticTexCoords);
                    if (voxelBridgeScene.has(standardSemanticTexCoords
                            ? "midTexCoordTexCoord"
                            : "midTexCoordAttribute")) {
                        if (midTexCoord == null || midTexCoord.components() != 2) {
                            throw new IOException(
                                    "VOXELBRIDGE_minecraft_scene primitive requires VEC2 "
                                            + (standardSemanticTexCoords
                                                    ? "TEXCOORD_3"
                                                    : "_VOXELBRIDGE_MID_TEX_COORD"));
                        }
                        validateMidTexCoord(uv0, midTexCoord);
                    }
                    if (voxelBridgeScene.has("midBlockAttribute")
                            && (midBlock == null || midBlock.components() != 4)) {
                        throw new IOException(
                                "VOXELBRIDGE_minecraft_scene primitive requires VEC4 "
                                        + "_VOXELBRIDGE_MID_BLOCK");
                    }
                    int identityId = primitive.path("extensions")
                            .path("VOXELBRIDGE_minecraft_material")
                            .path("materialIdentity")
                            .asInt(-1);
                    if (identityId >= 0) {
                        JsonNode identities = array(voxelBridgeScene, "materialIdentities");
                        if (identityId >= identities.size()) {
                            throw new IOException(
                                    "primitive materialIdentity is outside materialIdentities");
                        }
                        if (voxelBridgeSceneVersion >= 3) {
                            validateFluidIdentity(identities.get(identityId));
                        }
                        validateMaterialIdentity(
                                materialIdentity, identityId, standardSemanticTexCoords);
                        if ("terrain".equals(
                                identities.get(identityId).path("objectClass").asText())) {
                            validateMidBlock(positions, midBlock);
                        }
                    }
                }

                long[] indices = primitive.has("indices")
                        ? accessors.readIndices(primitive.path("indices").asInt(-1))
                        : sequentialIndices(positions.count());
                if (indices.length % 3 != 0) {
                    throw new IOException("TRIANGLES index count must be divisible by three");
                }

                int materialIndex = primitive.path("material").asInt(-1);
                String materialName = materialName(root, materialIndex);
                primitiveAttributeStats.add(attributeStats(
                        materialName, positions.count(), uv0, colors, epsilon));
                List<String> triangles = new ArrayList<>(indices.length / 3);
                List<String> appearanceTriangles = new ArrayList<>(indices.length / 3);
                for (int i = 0; i < indices.length; i += 3) {
                    String a = vertexToken(indices[i], positions, normals, uv0, uv1, colors, epsilon);
                    String b = vertexToken(indices[i + 1], positions, normals, uv0, uv1, colors, epsilon);
                    String c = vertexToken(indices[i + 2], positions, normals, uv0, uv1, colors, epsilon);
                    triangles.add(canonicalTriangle(a, b, c));
                    appearanceTriangles.add(appearanceTriangle(
                            root, materialIndex,
                            indices[i], indices[i + 1], indices[i + 2],
                            positions, uv0, colors, imageInfos, epsilon));
                    triangleGeometry.add(new TriangleGeometry(
                            materialName,
                            positionAt(indices[i], positions),
                            positionAt(indices[i + 1], positions),
                            positionAt(indices[i + 2], positions)));
                }

                for (double[] position : positions.values()) {
                    for (int component = 0; component < 3; component++) {
                        double value = requireFinite(position[component], "POSITION");
                        boundsMin[component] = Math.min(boundsMin[component], value);
                        boundsMax[component] = Math.max(boundsMax[component], value);
                    }
                }

                MaterialAccumulator accumulator = materialData.computeIfAbsent(
                        materialName, ignored -> new MaterialAccumulator());
                accumulator.primitiveCount++;
                accumulator.vertexCount += positions.count();
                accumulator.triangleCount += indices.length / 3L;
                accumulator.triangles.addAll(triangles);
                accumulator.appearanceTriangles.addAll(appearanceTriangles);
                accumulator.textureHashes.addAll(materialTextureHashes(root, materialIndex, imageInfos));

                primitiveCount++;
                totalVertices += positions.count();
                totalTriangles += indices.length / 3L;
            }
        }

        if (primitiveCount == 0) {
            throw new IOException("glTF contains no mesh primitives");
        }

        List<MaterialSnapshot> materialSnapshots = new ArrayList<>();
        MessageDigest overallGeometry = sha256();
        MessageDigest overallAppearance = sha256();
        for (Map.Entry<String, MaterialAccumulator> entry : materialData.entrySet()) {
            MaterialAccumulator value = entry.getValue();
            Collections.sort(value.triangles);
            Collections.sort(value.appearanceTriangles);
            String geometryHash = hashStrings(value.triangles);
            String appearanceHash = hashStrings(value.appearanceTriangles);
            updateString(overallGeometry, entry.getKey());
            updateString(overallGeometry, geometryHash);
            updateString(overallAppearance, entry.getKey());
            updateString(overallAppearance, appearanceHash);
            materialSnapshots.add(new MaterialSnapshot(
                    entry.getKey(),
                    value.primitiveCount,
                    value.vertexCount,
                    value.triangleCount,
                    geometryHash,
                    appearanceHash,
                    List.copyOf(value.textureHashes)));
        }

        List<AssertionSnapshot> assertionSnapshots = evaluateAssertions(
                scenarioManifest, materialSnapshots, triangleGeometry,
                primitiveAttributeStats, epsilon);
        String scenarioHash = scenarioHash(scenarioFile, scenarioManifest);
        return new GoldenSnapshot(
                SNAPSHOT_SCHEMA_VERSION,
                scenario,
                minecraftVersion,
                scenarioHash,
                array(root, "scenes").size(),
                array(root, "nodes").size(),
                meshes.size(),
                primitiveCount,
                totalVertices,
                totalTriangles,
                imageInfos.size(),
                quantizedVector(boundsMin, epsilon),
                quantizedVector(boundsMax, epsilon),
                HexFormat.of().formatHex(overallGeometry.digest()),
                HexFormat.of().formatHex(overallAppearance.digest()),
                assertionSnapshots,
                List.copyOf(materialSnapshots),
                imageSnapshots);
    }

    private static double[] positionAt(long index, AccessorData positions) throws IOException {
        if (index < 0 || index >= positions.count()) {
            throw new IOException("Index " + index + " is outside POSITION count " + positions.count());
        }
        return positions.values()[Math.toIntExact(index)];
    }

    private static AccessorData optionalAccessor(Accessors accessors, JsonNode attributes, String name)
            throws IOException {
        return attributes.has(name) ? accessors.read(attributes.path(name).asInt(-1)) : null;
    }

    private static AccessorData optionalUvAccessor(
            Accessors accessors, JsonNode attributes, String name) throws IOException {
        AccessorData data = optionalAccessor(accessors, attributes, name);
        if (data == null) {
            return null;
        }
        double[][] values = data.values();
        double[][] sanitized = null;
        for (int element = 0; element < values.length; element++) {
            for (int component = 0; component < values[element].length; component++) {
                if (!Double.isFinite(values[element][component])) {
                    if (sanitized == null) {
                        sanitized = new double[values.length][];
                        for (int copy = 0; copy < values.length; copy++) {
                            sanitized[copy] = values[copy].clone();
                        }
                    }
                    sanitized[element][component] = 0.0;
                }
            }
        }
        return sanitized != null ? new AccessorData(sanitized, data.components()) : data;
    }

    private static void validateAttributeCount(AccessorData positions, AccessorData attribute, String name)
            throws IOException {
        if (attribute != null && attribute.count() != positions.count()) {
            throw new IOException(name + " count does not match POSITION count");
        }
    }

    private static void validateFiniteAccessor(AccessorData accessor, String name)
            throws IOException {
        if (accessor == null) {
            return;
        }
        for (double[] element : accessor.values()) {
            for (double value : element) {
                requireFinite(value, name);
            }
        }
    }

    private static void validateVoxelBridgeLightmap(
            JsonNode root, List<ImageInfo> imageInfos) throws IOException {
        JsonNode scene = root.path("extensions").path("VOXELBRIDGE_minecraft_scene");
        if (!scene.has("lightmapTexture")) {
            return;
        }
        if (!"minecraft-light-texture-16x16".equals(
                scene.path("lightmapEncoding").asText())) {
            throw new IOException("VoxelBridge lightmap has an unknown encoding");
        }
        if (!"linear".equals(scene.path("lightmapColorSpace").asText())) {
            throw new IOException("VoxelBridge lightmap must declare linear color space");
        }
        int textureIndex = scene.path("lightmapTexture").asInt(-1);
        JsonNode textures = array(root, "textures");
        if (textureIndex < 0 || textureIndex >= textures.size()) {
            throw new IOException("VoxelBridge lightmapTexture index is out of range");
        }
        int source = textures.get(textureIndex).path("source").asInt(-1);
        if (source < 0 || source >= imageInfos.size()) {
            throw new IOException("VoxelBridge lightmap image source is out of range");
        }
        ImageSnapshot snapshot = imageInfos.get(source).snapshot();
        if (snapshot.width() != 16 || snapshot.height() != 16) {
            throw new IOException(
                    "VoxelBridge lightmap must be 16x16, got "
                            + snapshot.width() + "x" + snapshot.height());
        }
    }

    private static void validateSemanticTexCoordContract(JsonNode scene) throws IOException {
        int version = scene.path("version").asInt(-1);
        if ((version != 2 && version != 3)
                || scene.path("colorUvTexCoord").asInt(-1) != 1
                || scene.path("lightUvTexCoord").asInt(-1) != 2
                || scene.path("midTexCoordTexCoord").asInt(-1) != 3
                || scene.path("materialIdentityTexCoord").asInt(-1) != 4
                || !"normalized-minecraft-0-240".equals(
                        scene.path("lightUvEncoding").asText())
                || !"index-in-u-into-materialIdentities".equals(
                        scene.path("materialIdentityEncoding").asText())) {
            throw new IOException(
                    "VOXELBRIDGE_minecraft_scene has an unsupported TEXCOORD layout");
        }
    }

    private static void validateFluidIdentity(JsonNode identity) throws IOException {
        boolean fluid = identity.path("isFluid").asBoolean(false);
        boolean hasFluidFields = identity.has("fluidId")
                || identity.has("fluidState")
                || identity.has("irisRenderType");
        if (!fluid && hasFluidFields) {
            throw new IOException(
                    "non-fluid VoxelBridge identity declares fluid-only fields");
        }
        if (!fluid) {
            return;
        }
        if (identity.path("fluidId").asText("").isBlank()
                || identity.path("fluidState").asText("").isBlank()) {
            throw new IOException(
                    "fluid VoxelBridge identity requires fluidId and fluidState");
        }
        if (identity.path("irisRenderType").asInt(-1) != 1) {
            throw new IOException(
                    "fluid VoxelBridge identity must declare irisRenderType=1");
        }
    }

    private static void validateLightUv(
            AccessorData lightUv, boolean normalized) throws IOException {
        String semantic = normalized ? "TEXCOORD_2 light UV" : "_VOXELBRIDGE_LIGHT_UV";
        double upperBound = normalized ? 1.0 : 240.0;
        for (double[] value : lightUv.values()) {
            for (double component : value) {
                double finite = requireFinite(component, semantic);
                if (finite < 0.0 || finite > upperBound) {
                    throw new IOException(
                            semantic + " must be within 0.." + upperBound);
                }
            }
        }
    }

    private static void validateTangents(AccessorData tangents) throws IOException {
        for (double[] tangent : tangents.values()) {
            double lengthSquared = 0.0;
            for (int component = 0; component < 3; component++) {
                double value = requireFinite(tangent[component], "TANGENT");
                lengthSquared += value * value;
            }
            double handedness = requireFinite(tangent[3], "TANGENT.w");
            if (Math.abs(lengthSquared - 1.0) > 1.0e-3) {
                throw new IOException("TANGENT.xyz must be normalized");
            }
            if (Math.abs(Math.abs(handedness) - 1.0) > 1.0e-6) {
                throw new IOException("TANGENT.w must be -1 or 1");
            }
        }
    }

    private static void validateMidTexCoord(
            AccessorData uv0, AccessorData midTexCoord) throws IOException {
        if (uv0 == null || uv0.components() != 2) {
            throw new IOException(
                    "_VOXELBRIDGE_MID_TEX_COORD requires VEC2 TEXCOORD_0");
        }
        if (uv0.count() % 4 != 0) {
            throw new IOException(
                    "_VOXELBRIDGE_MID_TEX_COORD requires quad-expanded vertices");
        }
        for (int base = 0; base < uv0.count(); base += 4) {
            double expectedU = 0.0;
            double expectedV = 0.0;
            for (int vertex = 0; vertex < 4; vertex++) {
                expectedU += requireFinite(
                        uv0.values()[base + vertex][0], "TEXCOORD_0");
                expectedV += requireFinite(
                        uv0.values()[base + vertex][1], "TEXCOORD_0");
            }
            expectedU *= 0.25;
            expectedV *= 0.25;
            for (int vertex = 0; vertex < 4; vertex++) {
                double[] actual = midTexCoord.values()[base + vertex];
                if (Math.abs(requireFinite(
                            actual[0], "_VOXELBRIDGE_MID_TEX_COORD") - expectedU) > 1.0e-5
                        || Math.abs(requireFinite(
                            actual[1], "_VOXELBRIDGE_MID_TEX_COORD") - expectedV) > 1.0e-5) {
                    throw new IOException(
                            "_VOXELBRIDGE_MID_TEX_COORD is not the quad UV center");
                }
            }
        }
    }

    private static void validateMaterialIdentity(
            AccessorData materialIdentity, int expectedId, boolean standardTexCoord)
            throws IOException {
        int expectedComponents = standardTexCoord ? 2 : 1;
        String semantic = standardTexCoord
                ? "TEXCOORD_4 material identity"
                : "_VOXELBRIDGE_MATERIAL_ID";
        if (materialIdentity == null
                || materialIdentity.components() != expectedComponents) {
            throw new IOException(
                    "materialIdentity primitive requires "
                            + (standardTexCoord ? "VEC2 TEXCOORD_4" : "scalar "
                                    + "_VOXELBRIDGE_MATERIAL_ID"));
        }
        for (double[] value : materialIdentity.values()) {
            double actual = requireFinite(value[0], semantic);
            if (actual != expectedId) {
                throw new IOException(
                        semantic + " does not match primitive identity");
            }
            if (standardTexCoord
                    && requireFinite(value[1], semantic + ".y") != 0.0) {
                throw new IOException(
                        "TEXCOORD_4 material identity must reserve y as zero");
            }
        }
    }

    private static void validateMidBlock(
            AccessorData positions, AccessorData midBlock) throws IOException {
        if (positions.count() % 4 != 0) {
            throw new IOException("_VOXELBRIDGE_MID_BLOCK requires quad-expanded vertices");
        }
        for (int base = 0; base < positions.count(); base += 4) {
            double[] expectedCenter = new double[3];
            for (int component = 0; component < 3; component++) {
                expectedCenter[component] =
                        requireFinite(positions.values()[base][component], "POSITION")
                                + requireFinite(
                                    midBlock.values()[base][component],
                                    "_VOXELBRIDGE_MID_BLOCK") / 64.0;
            }
            double emission = requireFinite(
                    midBlock.values()[base][3], "_VOXELBRIDGE_MID_BLOCK.w");
            if (emission < 0.0 || emission > 15.0 || emission != Math.rint(emission)) {
                throw new IOException(
                        "_VOXELBRIDGE_MID_BLOCK.w must be an integer in 0..15");
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                for (int component = 0; component < 3; component++) {
                    double center =
                            requireFinite(
                                positions.values()[base + vertex][component], "POSITION")
                                    + requireFinite(
                                        midBlock.values()[base + vertex][component],
                                        "_VOXELBRIDGE_MID_BLOCK") / 64.0;
                    if (Math.abs(center - expectedCenter[component]) > 1.0e-5) {
                        throw new IOException(
                                "_VOXELBRIDGE_MID_BLOCK vertices disagree on block center");
                    }
                }
                if (requireFinite(
                        midBlock.values()[base + vertex][3],
                        "_VOXELBRIDGE_MID_BLOCK.w") != emission) {
                    throw new IOException(
                            "_VOXELBRIDGE_MID_BLOCK emission varies within a quad");
                }
            }
        }
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static long[] sequentialIndices(int count) {
        long[] result = new long[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    private static String vertexToken(
            long index,
            AccessorData positions,
            AccessorData normals,
            AccessorData uv0,
            AccessorData uv1,
            AccessorData colors,
            double epsilon) throws IOException {
        if (index < 0 || index >= positions.count()) {
            throw new IOException("Index " + index + " is outside POSITION count " + positions.count());
        }
        int i = Math.toIntExact(index);
        StringBuilder token = new StringBuilder(128);
        appendAttribute(token, "p", positions.values()[i], epsilon);
        appendAttribute(token, "n", normals == null ? null : normals.values()[i], epsilon);
        appendAttribute(token, "u0", uv0 == null ? null : uv0.values()[i], epsilon);
        appendAttribute(token, "u1", uv1 == null ? null : uv1.values()[i], epsilon);
        appendAttribute(token, "c", colors == null ? null : colors.values()[i], epsilon);
        return token.toString();
    }

    private static void appendAttribute(StringBuilder target, String name, double[] values, double epsilon)
            throws IOException {
        target.append(name).append('=');
        if (values == null) {
            target.append('-').append(';');
            return;
        }
        for (double value : values) {
            target.append(quantize(requireFinite(value, name), epsilon)).append(',');
        }
        target.append(';');
    }

    private static String canonicalTriangle(String a, String b, String c) {
        String first = a + "|" + b + "|" + c;
        String second = b + "|" + c + "|" + a;
        String third = c + "|" + a + "|" + b;
        return first.compareTo(second) <= 0
                ? (first.compareTo(third) <= 0 ? first : third)
                : (second.compareTo(third) <= 0 ? second : third);
    }

    private static void validateMarkerOnlyEmissiveMaterials(JsonNode root) throws IOException {
        JsonNode materials = array(root, "materials");
        for (int index = 0; index < materials.size(); index++) {
            JsonNode material = materials.get(index);
            JsonNode extras = material.path("extras");
            JsonNode extension = material.path("extensions")
                    .path("VOXELBRIDGE_minecraft_material");
            JsonNode extrasMarker = extras.get("voxelbridge:emissive");
            JsonNode extensionMarker = extension.get("emissive");

            if (extrasMarker != null && extensionMarker != null
                    && extrasMarker.asBoolean() != extensionMarker.asBoolean()) {
                throw new IOException("VoxelBridge emissive markers disagree for material "
                        + materialLabel(material, index));
            }

            boolean emissiveMarker = (extrasMarker != null && extrasMarker.asBoolean())
                    || (extensionMarker != null && extensionMarker.asBoolean());
            if (!emissiveMarker) {
                continue;
            }

            boolean hasStandardEmission = material.has("emissiveFactor")
                    || material.has("emissiveTexture")
                    || material.path("extensions").has("KHR_materials_emissive_strength");
            if (hasStandardEmission) {
                throw new IOException("VoxelBridge marker-only emissive material must not "
                        + "define standard glTF emission properties: "
                        + materialLabel(material, index));
            }
        }
    }

    private static String materialLabel(JsonNode material, int index) {
        String name = material.path("name").asText("");
        return name.isBlank() ? "#" + index : "'" + name + "' (#" + index + ")";
    }

    /**
     * Hashes sampled visible color together with geometry, but deliberately not
     * raw UV coordinates or image dimensions. Equivalent individual textures
     * and packed atlases therefore retain the same appearance hash while UV
     * orientation, tint, or sampled texture changes still invalidate it.
     */
    private static String appearanceTriangle(
            JsonNode root,
            int materialIndex,
            long ia,
            long ib,
            long ic,
            AccessorData positions,
            AccessorData uv0,
            AccessorData colors,
            List<ImageInfo> images,
            double epsilon) throws IOException {
        long[] indices = {ia, ib, ic};
        double[][] barycentrics = {
                {0.80, 0.10, 0.10},
                {0.10, 0.80, 0.10},
                {0.10, 0.10, 0.80}
        };
        double[] baseFactor = baseColorFactor(root, materialIndex);
        BufferedImage texture = baseColorImage(root, materialIndex, images);
        String[] tokens = new String[3];
        for (int vertex = 0; vertex < 3; vertex++) {
            double[] barycentric = barycentrics[vertex];
            double[] rgba = baseFactor.clone();
            if (texture != null && uv0 != null && uv0.components() >= 2) {
                double u = interpolate(uv0, indices, barycentric, 0);
                double v = interpolate(uv0, indices, barycentric, 1);
                int argb = sampleTexture(texture, u, v);
                rgba[0] *= ((argb >>> 16) & 0xff) / 255.0;
                rgba[1] *= ((argb >>> 8) & 0xff) / 255.0;
                rgba[2] *= (argb & 0xff) / 255.0;
                rgba[3] *= ((argb >>> 24) & 0xff) / 255.0;
            }
            if (colors != null) {
                int components = Math.min(colors.components(), 4);
                for (int component = 0; component < components; component++) {
                    rgba[component] *= interpolate(colors, indices, barycentric, component);
                }
            }
            double[] position = positionAt(indices[vertex], positions);
            tokens[vertex] = "p=" + quantized(position, epsilon)
                    + ";rgba=" + quantized(rgba, 1.0 / 255.0);
        }
        return canonicalTriangle(tokens[0], tokens[1], tokens[2]);
    }

    private static double interpolate(
            AccessorData data, long[] indices, double[] barycentric, int component)
            throws IOException {
        double result = 0.0;
        for (int index = 0; index < 3; index++) {
            long vertex = indices[index];
            if (vertex < 0 || vertex >= data.count()) {
                throw new IOException("Index " + vertex + " is outside attribute count " + data.count());
            }
            result += data.values()[Math.toIntExact(vertex)][component] * barycentric[index];
        }
        return result;
    }

    private static int sampleTexture(BufferedImage image, double u, double v) {
        double wrappedU = u - Math.floor(u);
        double wrappedV = v - Math.floor(v);
        int x = Math.min(image.getWidth() - 1, (int) Math.floor(wrappedU * image.getWidth()));
        int y = Math.min(image.getHeight() - 1,
                (int) Math.floor((1.0 - wrappedV) * image.getHeight()));
        if (y == image.getHeight()) {
            y = 0;
        }
        return image.getRGB(Math.max(0, x), Math.max(0, y));
    }

    private static double[] baseColorFactor(JsonNode root, int materialIndex) {
        double[] result = {1.0, 1.0, 1.0, 1.0};
        JsonNode materials = array(root, "materials");
        if (materialIndex < 0 || materialIndex >= materials.size()) {
            return result;
        }
        JsonNode factor = materials.get(materialIndex)
                .path("pbrMetallicRoughness").path("baseColorFactor");
        if (factor.isArray() && factor.size() == 4) {
            for (int index = 0; index < 4; index++) {
                result[index] = factor.get(index).asDouble(1.0);
            }
        }
        return result;
    }

    private static BufferedImage baseColorImage(
            JsonNode root, int materialIndex, List<ImageInfo> images) throws IOException {
        JsonNode materials = array(root, "materials");
        if (materialIndex < 0 || materialIndex >= materials.size()) {
            return null;
        }
        JsonNode textureInfo = materials.get(materialIndex)
                .path("pbrMetallicRoughness").path("baseColorTexture");
        if (!textureInfo.has("index")) {
            return null;
        }
        JsonNode textures = array(root, "textures");
        int textureIndex = textureInfo.path("index").asInt(-1);
        if (textureIndex < 0 || textureIndex >= textures.size()) {
            throw new IOException("Material references invalid base-color texture " + textureIndex);
        }
        int imageIndex = textures.get(textureIndex).path("source").asInt(-1);
        if (imageIndex < 0 || imageIndex >= images.size()) {
            throw new IOException("Base-color texture references invalid image " + imageIndex);
        }
        return images.get(imageIndex).decoded();
    }

    private static String quantized(double[] values, double epsilon) throws IOException {
        StringBuilder result = new StringBuilder();
        for (double value : values) {
            result.append(quantize(requireFinite(value, "appearance"), epsilon)).append(',');
        }
        return result.toString();
    }

    private static String materialName(JsonNode root, int materialIndex) {
        JsonNode materials = array(root, "materials");
        if (materialIndex < 0 || materialIndex >= materials.size()) {
            return "__no_material__";
        }
        String name = materials.get(materialIndex).path("name").asText("");
        return name.isBlank() ? "material#" + materialIndex : name;
    }

    private static Set<String> materialTextureHashes(
            JsonNode root, int materialIndex, List<ImageInfo> imageInfos) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        JsonNode materials = array(root, "materials");
        if (materialIndex < 0 || materialIndex >= materials.size()) {
            return result;
        }

        JsonNode material = materials.get(materialIndex);
        List<JsonNode> textureInfos = new ArrayList<>();
        JsonNode pbr = material.path("pbrMetallicRoughness");
        textureInfos.add(pbr.path("baseColorTexture"));
        textureInfos.add(pbr.path("metallicRoughnessTexture"));
        textureInfos.add(material.path("normalTexture"));
        textureInfos.add(material.path("occlusionTexture"));
        textureInfos.add(material.path("emissiveTexture"));

        JsonNode textures = array(root, "textures");
        for (JsonNode textureInfo : textureInfos) {
            if (!textureInfo.isObject() || !textureInfo.has("index")) {
                continue;
            }
            int textureIndex = textureInfo.path("index").asInt(-1);
            if (textureIndex < 0 || textureIndex >= textures.size()) {
                throw new IOException("Material references invalid texture index " + textureIndex);
            }
            int imageIndex = textures.get(textureIndex).path("source").asInt(-1);
            if (imageIndex < 0 || imageIndex >= imageInfos.size()) {
                throw new IOException("Texture references invalid image index " + imageIndex);
            }
            result.add(imageInfos.get(imageIndex).snapshot().rgbaHash());
        }
        return result;
    }

    private static List<AssertionSnapshot> evaluateAssertions(
            Path scenarioManifest,
            List<MaterialSnapshot> materials,
            List<TriangleGeometry> triangles,
            List<PrimitiveAttributeStats> primitiveAttributes,
            double epsilon) throws IOException {
        if (scenarioManifest == null) {
            return List.of();
        }
        if (!Files.isRegularFile(scenarioManifest)) {
            throw new IOException("Scenario manifest does not exist: " + scenarioManifest);
        }

        JsonNode manifest = JSON.readTree(scenarioManifest.toFile());
        int schemaVersion = manifest.path("schemaVersion").asInt(-1);
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw new IOException("Unsupported scenario manifest schemaVersion " + schemaVersion
                    + ": " + scenarioManifest);
        }
        JsonNode definitions = manifest.path("assertions");
        if (definitions.isMissingNode() || definitions.isNull()) {
            return List.of();
        }
        if (!definitions.isArray()) {
            throw new IOException("Scenario manifest assertions must be an array: " + scenarioManifest);
        }

        double[] center = selectionCenter(manifest.path("selection"));
        String coordinateMode = manifest.path("export").path("coordinateMode").asText("centered");
        boolean centeredCoordinates = !"world_origin".equalsIgnoreCase(coordinateMode)
                && !"world-origin".equalsIgnoreCase(coordinateMode);
        Set<String> ids = new LinkedHashSet<>();
        List<AssertionSnapshot> results = new ArrayList<>();
        for (JsonNode definition : definitions) {
            String id = requiredText(definition, "id", "semantic assertion");
            if (!ids.add(id)) {
                throw new IOException("Duplicate semantic assertion id: " + id);
            }
            String type = requiredText(definition, "type", "semantic assertion " + id);
            Pattern materialPattern = compilePattern(
                    requiredText(definition, "materialRegex", "semantic assertion " + id), id);

            List<MaterialSnapshot> matchingMaterials = materials.stream()
                    .filter(material -> materialPattern.matcher(material.name()).find())
                    .toList();
            int materialCount = matchingMaterials.size();
            int primitiveCount = matchingMaterials.stream()
                    .mapToInt(MaterialSnapshot::primitiveCount)
                    .sum();
            long vertexCount = matchingMaterials.stream()
                    .mapToLong(MaterialSnapshot::vertexCount)
                    .sum();
            long triangleCount = matchingMaterials.stream()
                    .mapToLong(MaterialSnapshot::triangleCount)
                    .sum();
            List<PrimitiveAttributeStats> matchingAttributes = primitiveAttributes.stream()
                    .filter(stats -> materialPattern.matcher(stats.material()).find())
                    .toList();
            long colorVertices = matchingAttributes.stream()
                    .mapToLong(PrimitiveAttributeStats::colorVertices)
                    .sum();
            long nonBlackColorVertices = matchingAttributes.stream()
                    .mapToLong(PrimitiveAttributeStats::nonBlackColorVertices)
                    .sum();
            long nonWhiteColorVertices = matchingAttributes.stream()
                    .mapToLong(PrimitiveAttributeStats::nonWhiteColorVertices)
                    .sum();
            long uvVertices = matchingAttributes.stream()
                    .mapToLong(PrimitiveAttributeStats::uvVertices)
                    .sum();
            long outOfRangeUvVertices = matchingAttributes.stream()
                    .mapToLong(PrimitiveAttributeStats::outOfRangeUvVertices)
                    .sum();
            long fullRangeUvPrimitives = matchingAttributes.stream()
                    .filter(PrimitiveAttributeStats::fullRangeUv)
                    .count();
            double maxUvSpanU = matchingAttributes.stream()
                    .mapToDouble(PrimitiveAttributeStats::uvSpanU)
                    .max()
                    .orElse(0.0);
            double maxUvSpanV = matchingAttributes.stream()
                    .mapToDouble(PrimitiveAttributeStats::uvSpanV)
                    .max()
                    .orElse(0.0);

            if ("face".equals(type)) {
                FaceSelector face = parseFaceSelector(
                        definition.path("face"), center, centeredCoordinates, epsilon, id);
                triangleCount = triangles.stream()
                        .filter(triangle -> materialPattern.matcher(triangle.material()).find())
                        .filter(face::matches)
                        .count();
                vertexCount = triangleCount * 3L;
            } else if (!"material".equals(type)) {
                throw new IOException("Semantic assertion " + id
                        + " has unsupported type '" + type + "'");
            }

            assertMetric(definition, id, "Materials", materialCount);
            assertMetric(definition, id, "Primitives", primitiveCount);
            assertMetric(definition, id, "Vertices", vertexCount);
            assertMetric(definition, id, "Triangles", triangleCount);
            assertMetric(definition, id, "ColorVertices", colorVertices);
            assertMetric(definition, id, "NonBlackColorVertices", nonBlackColorVertices);
            assertMetric(definition, id, "NonWhiteColorVertices", nonWhiteColorVertices);
            assertMetric(definition, id, "UvVertices", uvVertices);
            assertMetric(definition, id, "OutOfRangeUvVertices", outOfRangeUvVertices);
            assertMetric(definition, id, "FullRangeUvPrimitives", fullRangeUvPrimitives);
            assertMaximumDouble(definition, id, "maxUvSpanU", maxUvSpanU, epsilon);
            assertMaximumDouble(definition, id, "maxUvSpanV", maxUvSpanV, epsilon);
            results.add(new AssertionSnapshot(
                    id, type, materialCount, primitiveCount, vertexCount, triangleCount));
        }
        return List.copyOf(results);
    }

    private static PrimitiveAttributeStats attributeStats(
            String material,
            int vertexCount,
            AccessorData uv0,
            AccessorData colors,
            double epsilon) throws IOException {
        long nonBlackColorVertices = 0;
        long nonWhiteColorVertices = 0;
        if (colors != null) {
            for (double[] color : colors.values()) {
                int rgbComponents = Math.min(3, color.length);
                boolean nonBlack = false;
                boolean nonWhite = false;
                for (int component = 0; component < rgbComponents; component++) {
                    double value = requireFinite(color[component], "COLOR_0");
                    nonBlack |= value > epsilon;
                    nonWhite |= value < 1.0 - epsilon;
                }
                if (nonBlack) {
                    nonBlackColorVertices++;
                }
                if (nonWhite) {
                    nonWhiteColorVertices++;
                }
            }
        }

        long outOfRangeUvVertices = 0;
        boolean fullRangeUv = false;
        double uvSpanU = 0.0;
        double uvSpanV = 0.0;
        if (uv0 != null) {
            double minU = Double.POSITIVE_INFINITY;
            double minV = Double.POSITIVE_INFINITY;
            double maxU = Double.NEGATIVE_INFINITY;
            double maxV = Double.NEGATIVE_INFINITY;
            for (double[] uv : uv0.values()) {
                double u = requireFinite(uv[0], "TEXCOORD_0");
                double v = requireFinite(uv[1], "TEXCOORD_0");
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
                if (u < -epsilon || u > 1.0 + epsilon || v < -epsilon || v > 1.0 + epsilon) {
                    outOfRangeUvVertices++;
                }
            }
            fullRangeUv = minU <= epsilon && minV <= epsilon
                    && maxU >= 1.0 - epsilon && maxV >= 1.0 - epsilon;
            uvSpanU = maxU - minU;
            uvSpanV = maxV - minV;
        }

        return new PrimitiveAttributeStats(
                material,
                colors == null ? 0 : vertexCount,
                nonBlackColorVertices,
                nonWhiteColorVertices,
                uv0 == null ? 0 : vertexCount,
                outOfRangeUvVertices,
                fullRangeUv,
                uvSpanU,
                uvSpanV);
    }

    private static boolean requiresSceneContract(Path scenarioManifest) throws IOException {
        if (scenarioManifest == null || !Files.isRegularFile(scenarioManifest)) {
            return false;
        }
        return JSON.readTree(scenarioManifest.toFile()).path("requireSceneContract").asBoolean(false);
    }

    private static void assertMaximumDouble(
            JsonNode definition,
            String assertionId,
            String field,
            double actual,
            double epsilon) throws IOException {
        JsonNode node = definition.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isNumber()) {
            throw new IOException("Semantic assertion " + assertionId
                    + " requires " + field + " to be a non-negative number");
        }
        double maximum = node.asDouble(Double.NaN);
        if (!Double.isFinite(maximum) || maximum < 0.0) {
            throw new IOException("Semantic assertion " + assertionId
                    + " requires " + field + " to be a finite non-negative number");
        }
        if (actual > maximum + epsilon) {
            throw new AssertionError("Semantic assertion '" + assertionId + "' failed: "
                    + field + "=" + maximum + ", actual=" + actual);
        }
    }

    private static FaceSelector parseFaceSelector(
            JsonNode face,
            double[] center,
            boolean centeredCoordinates,
            double epsilon,
            String assertionId) throws IOException {
        if (!face.isObject()) {
            throw new IOException("Semantic face assertion " + assertionId + " is missing a face object");
        }
        String axisName = requiredText(face, "axis", "semantic face assertion " + assertionId);
        int axis = switch (axisName) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> throw new IOException("Semantic face assertion " + assertionId
                    + " has invalid axis '" + axisName + "'");
        };
        if (!face.has("coordinate") || !face.path("coordinate").isNumber()) {
            throw new IOException("Semantic face assertion " + assertionId
                    + " requires a numeric coordinate");
        }

        String space = face.path("space").asText("world");
        if (!"world".equals(space) && !"gltf".equals(space)) {
            throw new IOException("Semantic face assertion " + assertionId
                    + " has invalid coordinate space '" + space + "'");
        }
        if ("world".equals(space) && centeredCoordinates && center == null) {
            throw new IOException("Semantic face assertion " + assertionId
                    + " uses world coordinates but the scenario has no valid selection");
        }

        double coordinate = requireFinite(face.path("coordinate").asDouble(), "face coordinate");
        if ("world".equals(space) && centeredCoordinates) {
            coordinate -= center[axis];
        }
        double tolerance = face.has("tolerance")
                ? requireFinite(face.path("tolerance").asDouble(), "face tolerance")
                : Math.max(1.0e-3, epsilon * 2.0);
        if (!(tolerance > 0.0)) {
            throw new IOException("Semantic face assertion " + assertionId
                    + " requires a positive tolerance");
        }

        double[][] bounds = {
                {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
                {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
                {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}
        };
        JsonNode boundsNode = face.path("bounds");
        if (!boundsNode.isMissingNode() && !boundsNode.isObject()) {
            throw new IOException("Semantic face assertion " + assertionId
                    + " bounds must be an object");
        }
        String[] axisNames = {"x", "y", "z"};
        for (int component = 0; component < 3; component++) {
            JsonNode range = boundsNode.path(axisNames[component]);
            if (range.isMissingNode()) {
                continue;
            }
            if (!range.isArray() || range.size() != 2
                    || !range.get(0).isNumber() || !range.get(1).isNumber()) {
                throw new IOException("Semantic face assertion " + assertionId
                        + " bound " + axisNames[component] + " must be [min, max]");
            }
            double min = requireFinite(range.get(0).asDouble(), "face bound");
            double max = requireFinite(range.get(1).asDouble(), "face bound");
            if (min > max) {
                throw new IOException("Semantic face assertion " + assertionId
                        + " bound " + axisNames[component] + " has min > max");
            }
            if ("world".equals(space) && centeredCoordinates) {
                min -= center[component];
                max -= center[component];
            }
            bounds[component][0] = min;
            bounds[component][1] = max;
        }
        return new FaceSelector(axis, coordinate, bounds, tolerance);
    }

    private static double[] selectionCenter(JsonNode selection) throws IOException {
        if (selection.isMissingNode() || selection.isNull()) {
            return null;
        }
        if (!selection.isObject()) {
            throw new IOException("Scenario selection must be an object");
        }
        double[] min = vector3(selection.path("min"), "scenario selection min");
        double[] max = vector3(selection.path("max"), "scenario selection max");
        return new double[] {
                (min[0] + max[0]) / 2.0,
                (min[1] + max[1]) / 2.0,
                (min[2] + max[2]) / 2.0
        };
    }

    private static double[] vector3(JsonNode node, String description) throws IOException {
        if (!node.isArray() || node.size() != 3) {
            throw new IOException(description + " must contain three numbers");
        }
        double[] result = new double[3];
        for (int i = 0; i < result.length; i++) {
            if (!node.get(i).isNumber()) {
                throw new IOException(description + " must contain three numbers");
            }
            result[i] = requireFinite(node.get(i).asDouble(), description);
        }
        return result;
    }

    private static String requiredText(JsonNode owner, String field, String description) throws IOException {
        String value = owner.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IOException(description + " requires a non-empty " + field);
        }
        return value;
    }

    private static Pattern compilePattern(String expression, String assertionId) throws IOException {
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            throw new IOException("Semantic assertion " + assertionId
                    + " has invalid materialRegex: " + e.getMessage(), e);
        }
    }

    private static void assertMetric(
            JsonNode definition,
            String assertionId,
            String suffix,
            long actual) throws IOException {
        String expectedField = "expected" + suffix;
        String minimumField = "min" + suffix;
        String maximumField = "max" + suffix;
        if (definition.has(expectedField)) {
            long expected = nonNegativeLong(definition.path(expectedField), expectedField, assertionId);
            if (actual != expected) {
                throw assertionFailure(assertionId, expectedField + "=" + expected, actual);
            }
        }
        if (definition.has(minimumField)) {
            long minimum = nonNegativeLong(definition.path(minimumField), minimumField, assertionId);
            if (actual < minimum) {
                throw assertionFailure(assertionId, minimumField + "=" + minimum, actual);
            }
        }
        if (definition.has(maximumField)) {
            long maximum = nonNegativeLong(definition.path(maximumField), maximumField, assertionId);
            if (actual > maximum) {
                throw assertionFailure(assertionId, maximumField + "=" + maximum, actual);
            }
        }
    }

    private static long nonNegativeLong(JsonNode node, String field, String assertionId) throws IOException {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0L) {
            throw new IOException("Semantic assertion " + assertionId
                    + " requires " + field + " to be a non-negative integer");
        }
        return node.asLong();
    }

    private static AssertionError assertionFailure(String id, String expected, long actual) {
        return new AssertionError("Semantic assertion '" + id + "' failed: "
                + expected + ", actual=" + actual);
    }

    private static String scenarioHash(Path scenarioFile, Path scenarioManifest) throws IOException {
        if (scenarioFile == null && scenarioManifest == null) {
            return "";
        }
        if (scenarioManifest == null) {
            return sha256(Files.readAllBytes(scenarioFile));
        }
        MessageDigest digest = sha256();
        if (scenarioFile != null) {
            updateString(digest, "scene.mcfunction");
            digest.update(Files.readAllBytes(scenarioFile));
            digest.update((byte) '\n');
        }
        updateString(digest, "scenario.json");
        digest.update(Files.readAllBytes(scenarioManifest));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<ImageInfo> readImages(JsonNode root, Accessors accessors, Path baseDir) throws IOException {
        List<ImageInfo> result = new ArrayList<>();
        Map<String, ImageInfo> decodedCache = new TreeMap<>();
        JsonNode images = array(root, "images");
        for (int i = 0; i < images.size(); i++) {
            JsonNode image = images.get(i);
            byte[] bytes;
            String id;
            if (image.has("uri")) {
                String uri = image.path("uri").asText();
                bytes = readUri(baseDir, uri);
                id = uri.startsWith("data:") ? "data:image#" + i : uri;
            } else if (image.has("bufferView")) {
                int viewIndex = image.path("bufferView").asInt(-1);
                bytes = accessors.readBufferView(viewIndex);
                id = "bufferView:" + viewIndex;
            } else {
                throw new IOException("Image " + i + " has neither uri nor bufferView");
            }

            ImageInfo cached = decodedCache.get(id);
            if (cached != null) {
                result.add(cached);
                continue;
            }

            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new IOException("Image could not be decoded: " + id);
            }
            MessageDigest rgba = sha256();
            byte[] pixel = new byte[4];
            for (int y = 0; y < decoded.getHeight(); y++) {
                for (int x = 0; x < decoded.getWidth(); x++) {
                    int argb = decoded.getRGB(x, y);
                    pixel[0] = (byte) ((argb >>> 16) & 0xff);
                    pixel[1] = (byte) ((argb >>> 8) & 0xff);
                    pixel[2] = (byte) (argb & 0xff);
                    pixel[3] = (byte) ((argb >>> 24) & 0xff);
                    rgba.update(pixel);
                }
            }
            ImageInfo info = new ImageInfo(new ImageSnapshot(
                    id,
                    decoded.getWidth(),
                    decoded.getHeight(),
                    HexFormat.of().formatHex(rgba.digest())), decoded);
            decodedCache.put(id, info);
            result.add(info);
        }
        return result;
    }

    private static byte[] readUri(Path baseDir, String uri) throws IOException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).contains(";base64")) {
                throw new IOException("Only base64 data URIs are supported");
            }
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        Path resolved = baseDir.resolve(decoded).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IOException("Asset URI escapes the glTF directory: " + uri);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Referenced asset does not exist: " + resolved);
        }
        return Files.readAllBytes(resolved);
    }

    private static List<Double> quantizedVector(double[] values, double epsilon) {
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values) {
            double readable = BigDecimal.valueOf(quantize(value, epsilon))
                    .multiply(BigDecimal.valueOf(epsilon))
                    .stripTrailingZeros()
                    .doubleValue();
            result.add(readable);
        }
        return List.copyOf(result);
    }

    private static long quantize(double value, double epsilon) {
        return Math.round(value / epsilon);
    }

    private static double requireFinite(double value, String semantic) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IOException("Non-finite value in " + semantic + ": " + value);
        }
        return value;
    }

    private static String hashStrings(List<String> values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            updateString(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateString(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static JsonNode array(JsonNode owner, String name) {
        JsonNode value = owner.path(name);
        return value.isArray() ? value : JSON.createArrayNode();
    }

    private record TriangleGeometry(String material, double[] a, double[] b, double[] c) {}

    private record FaceSelector(int axis, double coordinate, double[][] bounds, double tolerance) {
        boolean matches(TriangleGeometry triangle) {
            return matchesVertex(triangle.a())
                    && matchesVertex(triangle.b())
                    && matchesVertex(triangle.c());
        }

        private boolean matchesVertex(double[] vertex) {
            if (Math.abs(vertex[axis] - coordinate) > tolerance) {
                return false;
            }
            for (int component = 0; component < 3; component++) {
                if (component == axis) {
                    continue;
                }
                if (vertex[component] < bounds[component][0] - tolerance
                        || vertex[component] > bounds[component][1] + tolerance) {
                    return false;
                }
            }
            return true;
        }
    }

    private record ImageInfo(ImageSnapshot snapshot, BufferedImage decoded) {}

    private record PrimitiveAttributeStats(
            String material,
            long colorVertices,
            long nonBlackColorVertices,
            long nonWhiteColorVertices,
            long uvVertices,
            long outOfRangeUvVertices,
            boolean fullRangeUv,
            double uvSpanU,
            double uvSpanV) {}

    private static final class MaterialAccumulator {
        int primitiveCount;
        long vertexCount;
        long triangleCount;
        final List<String> triangles = new ArrayList<>();
        final List<String> appearanceTriangles = new ArrayList<>();
        final Set<String> textureHashes = new java.util.TreeSet<>();
    }

    private record AccessorData(double[][] values, int components) {
        int count() {
            return values.length;
        }
    }

    private static final class Accessors {
        private final JsonNode root;
        private final List<byte[]> buffers;

        Accessors(JsonNode root, Path baseDir) throws IOException {
            this.root = root;
            this.buffers = new ArrayList<>();
            JsonNode bufferNodes = array(root, "buffers");
            for (int i = 0; i < bufferNodes.size(); i++) {
                JsonNode buffer = bufferNodes.get(i);
                if (!buffer.has("uri")) {
                    throw new IOException("GLB buffers are not supported by the .gltf verifier");
                }
                byte[] bytes = readUri(baseDir, buffer.path("uri").asText());
                int declaredLength = buffer.path("byteLength").asInt(-1);
                if (declaredLength < 0 || bytes.length < declaredLength) {
                    throw new IOException("Buffer " + i + " is shorter than its declared byteLength");
                }
                buffers.add(bytes);
            }
        }

        AccessorData read(int accessorIndex) throws IOException {
            JsonNode accessorNodes = array(root, "accessors");
            if (accessorIndex < 0 || accessorIndex >= accessorNodes.size()) {
                throw new IOException("Invalid accessor index " + accessorIndex);
            }
            JsonNode accessor = accessorNodes.get(accessorIndex);
            if (accessor.has("sparse")) {
                throw new IOException("Sparse accessors are not supported by golden verification");
            }
            int viewIndex = accessor.path("bufferView").asInt(-1);
            View view = view(viewIndex);
            int componentType = accessor.path("componentType").asInt(-1);
            int componentSize = componentSize(componentType);
            int components = componentCount(accessor.path("type").asText());
            int count = accessor.path("count").asInt(-1);
            if (count < 0) {
                throw new IOException("Accessor has invalid count");
            }
            int elementSize = Math.multiplyExact(componentSize, components);
            int stride = view.byteStride == 0 ? elementSize : view.byteStride;
            if (stride < elementSize) {
                throw new IOException("bufferView byteStride is smaller than accessor element size");
            }
            int accessorOffset = accessor.path("byteOffset").asInt(0);
            long end = (long) view.byteOffset + accessorOffset
                    + (count == 0 ? 0 : (long) (count - 1) * stride + elementSize);
            if (accessorOffset < 0 || end > (long) view.byteOffset + view.byteLength
                    || end > view.bytes.length) {
                throw new IOException("Accessor " + accessorIndex + " exceeds its bufferView");
            }

            boolean normalized = accessor.path("normalized").asBoolean(false);
            double[][] values = new double[count][components];
            ByteBuffer data = ByteBuffer.wrap(view.bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int element = 0; element < count; element++) {
                int offset = view.byteOffset + accessorOffset + element * stride;
                for (int component = 0; component < components; component++) {
                    data.position(offset + component * componentSize);
                    values[element][component] = readComponent(data, componentType, normalized);
                }
            }
            return new AccessorData(values, components);
        }

        long[] readIndices(int accessorIndex) throws IOException {
            JsonNode accessor = array(root, "accessors").path(accessorIndex);
            if (!"SCALAR".equals(accessor.path("type").asText())) {
                throw new IOException("Index accessor must use SCALAR");
            }
            int componentType = accessor.path("componentType").asInt(-1);
            if (componentType != 5121 && componentType != 5123 && componentType != 5125) {
                throw new IOException("Index accessor must use an unsigned integer component type");
            }
            AccessorData data = read(accessorIndex);
            long[] result = new long[data.count()];
            for (int i = 0; i < result.length; i++) {
                result[i] = (long) data.values()[i][0];
            }
            return result;
        }

        byte[] readBufferView(int viewIndex) throws IOException {
            View view = view(viewIndex);
            return java.util.Arrays.copyOfRange(
                    view.bytes, view.byteOffset, view.byteOffset + view.byteLength);
        }

        private View view(int viewIndex) throws IOException {
            JsonNode views = array(root, "bufferViews");
            if (viewIndex < 0 || viewIndex >= views.size()) {
                throw new IOException("Invalid bufferView index " + viewIndex);
            }
            JsonNode view = views.get(viewIndex);
            int bufferIndex = view.path("buffer").asInt(-1);
            if (bufferIndex < 0 || bufferIndex >= buffers.size()) {
                throw new IOException("bufferView references invalid buffer " + bufferIndex);
            }
            byte[] bytes = buffers.get(bufferIndex);
            int offset = view.path("byteOffset").asInt(0);
            int length = view.path("byteLength").asInt(-1);
            int stride = view.path("byteStride").asInt(0);
            if (offset < 0 || length < 0 || (long) offset + length > bytes.length) {
                throw new IOException("bufferView exceeds its buffer");
            }
            return new View(bytes, offset, length, stride);
        }

        private static int componentSize(int componentType) throws IOException {
            return switch (componentType) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                case 5125, 5126 -> 4;
                default -> throw new IOException("Unsupported accessor componentType " + componentType);
            };
        }

        private static int componentCount(String type) throws IOException {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                case "MAT2" -> 4;
                case "MAT3" -> 9;
                case "MAT4" -> 16;
                default -> throw new IOException("Unsupported accessor type " + type);
            };
        }

        private static double readComponent(ByteBuffer data, int type, boolean normalized) {
            return switch (type) {
                case 5120 -> {
                    byte value = data.get();
                    yield normalized ? Math.max(value / 127.0, -1.0) : value;
                }
                case 5121 -> {
                    int value = Byte.toUnsignedInt(data.get());
                    yield normalized ? value / 255.0 : value;
                }
                case 5122 -> {
                    short value = data.getShort();
                    yield normalized ? Math.max(value / 32767.0, -1.0) : value;
                }
                case 5123 -> {
                    int value = Short.toUnsignedInt(data.getShort());
                    yield normalized ? value / 65535.0 : value;
                }
                case 5125 -> {
                    long value = Integer.toUnsignedLong(data.getInt());
                    yield normalized ? value / 4294967295.0 : value;
                }
                case 5126 -> data.getFloat();
                default -> throw new IllegalArgumentException("Unsupported component type " + type);
            };
        }

        private record View(byte[] bytes, int byteOffset, int byteLength, int byteStride) {}
    }
}
