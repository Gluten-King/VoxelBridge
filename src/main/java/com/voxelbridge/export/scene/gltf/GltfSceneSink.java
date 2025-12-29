package com.voxelbridge.export.scene.gltf;

import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.scene.SceneWriteRequest;
import de.javagl.jgltf.impl.v2.Buffer;
import de.javagl.jgltf.impl.v2.BufferView;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Mesh;
import de.javagl.jgltf.impl.v2.MeshPrimitive;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.impl.v2.Scene;
import de.javagl.jgltf.model.io.GltfWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class GltfSceneSink implements SceneSink, AutoCloseable {
    private final GlTF gltf;
    private final List<Float> positions = new ArrayList<>();
    private final List<Float> normals = new ArrayList<>();
    // private final List<Float> uvs = new ArrayList<>(); // TODO: Implement texture support
    private final List<Integer> indices = new ArrayList<>();
    private int vertexCount = 0;

    public GltfSceneSink(SceneWriteRequest request) {
        this.gltf = new GlTF();
        this.gltf.setAsset(new de.javagl.jgltf.impl.v2.Asset());
        this.gltf.getAsset().setGenerator("VoxelBridge");
        this.gltf.getAsset().setVersion("2.0");
    }

    @Override
    public void addQuad(String materialGroupKey, String spriteKey, String overlaySpriteKey, float[] pos, float[] uv0, float[] uv1, float[] normal, float[] colors, boolean doubleSided) {
        // Simplified implementation: Merge all vertices into one big mesh for now.
        // In a real implementation, you would batch by materialGroupKey.
        
        // Add vertices
        // pos is 12 floats (4 vertices * 3)
        for (int i = 0; i < 12; i++) {
            positions.add(pos[i]);
        }
        
        // Add normals
        // normal is 3 floats (1 face normal), repeat 4 times
        for (int i = 0; i < 4; i++) {
            normals.add(normal[0]);
            normals.add(normal[1]);
            normals.add(normal[2]);
        }

        // Add indices (0, 1, 2, 0, 2, 3) relative to current vertexCount
        indices.add(vertexCount + 0);
        indices.add(vertexCount + 1);
        indices.add(vertexCount + 2);
        indices.add(vertexCount + 0);
        indices.add(vertexCount + 2);
        indices.add(vertexCount + 3);

        vertexCount += 4;
    }

    @Override
    public Path write(SceneWriteRequest request) throws IOException {
        // Create Buffers
        byte[] posBytes = floatListToBytes(positions);
        byte[] normBytes = floatListToBytes(normals);
        byte[] idxBytes = intListToBytes(indices); // Should use short if possible, but int is safer for large meshes

        int totalSize = posBytes.length + normBytes.length + idxBytes.length;
        ByteBuffer bufferData = ByteBuffer.allocate(totalSize);
        bufferData.order(ByteOrder.LITTLE_ENDIAN);
        bufferData.put(posBytes);
        bufferData.put(normBytes);
        bufferData.put(idxBytes);

        // Define Buffer
        Buffer buffer = new Buffer();
        buffer.setByteLength(totalSize);
        buffer.setUri("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bufferData.array()));
        gltf.addBuffers(buffer);

        int bufferIndex = gltf.getBuffers().size() - 1;

        // Define BufferViews
        int posOffset = 0;
        int normOffset = posOffset + posBytes.length;
        int idxOffset = normOffset + normBytes.length;

        BufferView posView = new BufferView();
        posView.setBuffer(bufferIndex);
        posView.setByteOffset(posOffset);
        posView.setByteLength(posBytes.length);
        posView.setTarget(34962); // ARRAY_BUFFER
        gltf.addBufferViews(posView);
        int posViewIdx = gltf.getBufferViews().size() - 1;

        BufferView normView = new BufferView();
        normView.setBuffer(bufferIndex);
        normView.setByteOffset(normOffset);
        normView.setByteLength(normBytes.length);
        normView.setTarget(34962); // ARRAY_BUFFER
        gltf.addBufferViews(normView);
        int normViewIdx = gltf.getBufferViews().size() - 1;

        BufferView idxView = new BufferView();
        idxView.setBuffer(bufferIndex);
        idxView.setByteOffset(idxOffset);
        idxView.setByteLength(idxBytes.length);
        idxView.setTarget(34963); // ELEMENT_ARRAY_BUFFER
        gltf.addBufferViews(idxView);
        int idxViewIdx = gltf.getBufferViews().size() - 1;

        // Define Accessors
        de.javagl.jgltf.impl.v2.Accessor posAcc = new de.javagl.jgltf.impl.v2.Accessor();
        posAcc.setBufferView(posViewIdx);
        posAcc.setComponentType(5126); // FLOAT
        posAcc.setCount(vertexCount);
        posAcc.setType("VEC3");
        // TODO: compute min/max
        gltf.addAccessors(posAcc);
        int posAccIdx = gltf.getAccessors().size() - 1;

        de.javagl.jgltf.impl.v2.Accessor normAcc = new de.javagl.jgltf.impl.v2.Accessor();
        normAcc.setBufferView(normViewIdx);
        normAcc.setComponentType(5126); // FLOAT
        normAcc.setCount(vertexCount);
        normAcc.setType("VEC3");
        gltf.addAccessors(normAcc);
        int normAccIdx = gltf.getAccessors().size() - 1;

        de.javagl.jgltf.impl.v2.Accessor idxAcc = new de.javagl.jgltf.impl.v2.Accessor();
        idxAcc.setBufferView(idxViewIdx);
        idxAcc.setComponentType(5125); // UNSIGNED_INT
        idxAcc.setCount(indices.size());
        idxAcc.setType("SCALAR");
        gltf.addAccessors(idxAcc);
        int idxAccIdx = gltf.getAccessors().size() - 1;

        // Define Material
        de.javagl.jgltf.impl.v2.Material material = new de.javagl.jgltf.impl.v2.Material();
        de.javagl.jgltf.impl.v2.MaterialPbrMetallicRoughness pbr = new de.javagl.jgltf.impl.v2.MaterialPbrMetallicRoughness();
        pbr.setBaseColorFactor(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        pbr.setMetallicFactor(0.0f);
        pbr.setRoughnessFactor(1.0f);
        material.setPbrMetallicRoughness(pbr);
        material.setName("lod_material");
        gltf.addMaterials(material);
        int materialIdx = gltf.getMaterials().size() - 1;

        // Define Mesh
        MeshPrimitive primitive = new MeshPrimitive();
        primitive.addAttributes("POSITION", posAccIdx);
        primitive.addAttributes("NORMAL", normAccIdx);
        primitive.setIndices(idxAccIdx);
        primitive.setMode(4); // TRIANGLES
        primitive.setMaterial(materialIdx);

        Mesh mesh = new Mesh();
        mesh.addPrimitives(primitive);
        gltf.addMeshes(mesh);
        int meshIdx = gltf.getMeshes().size() - 1;

        // Define Node & Scene
        Node node = new Node();
        node.setMesh(meshIdx);
        gltf.addNodes(node);
        int nodeIdx = gltf.getNodes().size() - 1;

        Scene scene = new Scene();
        scene.addNodes(nodeIdx);
        gltf.addScenes(scene);
        gltf.setScene(gltf.getScenes().size() - 1);

        // Write
        Path outputPath = request.outputPath();
        try (OutputStream os = Files.newOutputStream(outputPath)) {
            GltfWriter writer = new GltfWriter();
            writer.write(gltf, os);
        }
        
        return outputPath;
    }

    private byte[] floatListToBytes(List<Float> floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.size() * 4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }

    private byte[] intListToBytes(List<Integer> ints) {
        ByteBuffer bb = ByteBuffer.allocate(ints.size() * 4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for (int i : ints) bb.putInt(i);
        return bb.array();
    }

    @Override
    public void close() {
        positions.clear();
        normals.clear();
        indices.clear();
    }
}
