package com.voxelbridge.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public final class SemanticGltfAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private SemanticGltfAnalyzer() {}

    public static GoldenSnapshot analyze(
            Path gltfPath,
            String scenario,
            String minecraftVersion,
            Path scenarioFile,
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

        JsonNode meshes = array(root, "meshes");
        Map<String, MaterialAccumulator> materialData = new TreeMap<>();
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
                AccessorData uv0 = optionalAccessor(accessors, attributes, "TEXCOORD_0");
                AccessorData uv1 = optionalAccessor(accessors, attributes, "TEXCOORD_1");
                AccessorData colors = optionalAccessor(accessors, attributes, "COLOR_0");
                validateAttributeCount(positions, normals, "NORMAL");
                validateAttributeCount(positions, uv0, "TEXCOORD_0");
                validateAttributeCount(positions, uv1, "TEXCOORD_1");
                validateAttributeCount(positions, colors, "COLOR_0");

                long[] indices = primitive.has("indices")
                        ? accessors.readIndices(primitive.path("indices").asInt(-1))
                        : sequentialIndices(positions.count());
                if (indices.length % 3 != 0) {
                    throw new IOException("TRIANGLES index count must be divisible by three");
                }

                List<String> triangles = new ArrayList<>(indices.length / 3);
                for (int i = 0; i < indices.length; i += 3) {
                    String a = vertexToken(indices[i], positions, normals, uv0, uv1, colors, epsilon);
                    String b = vertexToken(indices[i + 1], positions, normals, uv0, uv1, colors, epsilon);
                    String c = vertexToken(indices[i + 2], positions, normals, uv0, uv1, colors, epsilon);
                    triangles.add(canonicalTriangle(a, b, c));
                }

                for (double[] position : positions.values()) {
                    for (int component = 0; component < 3; component++) {
                        double value = requireFinite(position[component], "POSITION");
                        boundsMin[component] = Math.min(boundsMin[component], value);
                        boundsMax[component] = Math.max(boundsMax[component], value);
                    }
                }

                int materialIndex = primitive.path("material").asInt(-1);
                String materialName = materialName(root, materialIndex);
                MaterialAccumulator accumulator = materialData.computeIfAbsent(
                        materialName, ignored -> new MaterialAccumulator());
                accumulator.primitiveCount++;
                accumulator.vertexCount += positions.count();
                accumulator.triangleCount += indices.length / 3L;
                accumulator.triangles.addAll(triangles);
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
        for (Map.Entry<String, MaterialAccumulator> entry : materialData.entrySet()) {
            MaterialAccumulator value = entry.getValue();
            Collections.sort(value.triangles);
            String geometryHash = hashStrings(value.triangles);
            updateString(overallGeometry, entry.getKey());
            updateString(overallGeometry, geometryHash);
            materialSnapshots.add(new MaterialSnapshot(
                    entry.getKey(),
                    value.primitiveCount,
                    value.vertexCount,
                    value.triangleCount,
                    geometryHash,
                    List.copyOf(value.textureHashes)));
        }

        String scenarioHash = scenarioFile == null ? "" : sha256(Files.readAllBytes(scenarioFile));
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
                List.copyOf(materialSnapshots),
                imageSnapshots);
    }

    private static AccessorData optionalAccessor(Accessors accessors, JsonNode attributes, String name)
            throws IOException {
        return attributes.has(name) ? accessors.read(attributes.path(name).asInt(-1)) : null;
    }

    private static void validateAttributeCount(AccessorData positions, AccessorData attribute, String name)
            throws IOException {
        if (attribute != null && attribute.count() != positions.count()) {
            throw new IOException(name + " count does not match POSITION count");
        }
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
                    HexFormat.of().formatHex(rgba.digest())));
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

    private record ImageInfo(ImageSnapshot snapshot) {}

    private static final class MaterialAccumulator {
        int primitiveCount;
        long vertexCount;
        long triangleCount;
        final List<String> triangles = new ArrayList<>();
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
