package com.voxelbridge.export.scene.gltf;

import de.javagl.jgltf.impl.v2.Asset;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.impl.v2.Image;
import de.javagl.jgltf.impl.v2.Material;
import de.javagl.jgltf.impl.v2.Mesh;
import de.javagl.jgltf.impl.v2.Node;
import de.javagl.jgltf.impl.v2.Sampler;
import de.javagl.jgltf.impl.v2.Scene;
import de.javagl.jgltf.impl.v2.Texture;
import de.javagl.jgltf.model.io.GltfAsset;
import de.javagl.jgltf.model.io.GltfAssetWriter;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates and finalizes the glTF document; geometry streaming stays outside. */
final class GltfDocumentAssembler {
    static final int SCHEMA_VERSION = 1;

    private GltfDocumentAssembler() {}

    static GlTF createDocument() {
        GlTF gltf = new GlTF();
        Asset asset = new Asset();
        asset.setVersion("2.0");
        asset.setGenerator("VoxelBridge");
        asset.setExtras(Map.of("voxelbridge:schemaVersion", SCHEMA_VERSION));
        gltf.setAsset(asset);

        Sampler sampler = new Sampler();
        sampler.setMagFilter(9728);
        sampler.setMinFilter(9728);
        sampler.setWrapS(10497);
        sampler.setWrapT(10497);
        gltf.setSamplers(List.of(sampler));
        return gltf;
    }

    static void attachScene(
        GlTF gltf,
        List<Material> materials,
        List<Mesh> meshes,
        List<Node> nodes,
        List<Texture> textures,
        List<Image> images
    ) {
        Scene scene = new Scene();
        List<Integer> nodeIndices = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) nodeIndices.add(i);
        nodeIndices.sort(java.util.Comparator.comparingInt(index ->
            alphaDrawOrder(nodes.get(index), meshes, materials)));
        scene.setNodes(nodeIndices);
        gltf.addScenes(scene);
        gltf.setScene(0);
        gltf.setMeshes(meshes);
        gltf.setMaterials(materials);
        gltf.setNodes(nodes);
        gltf.setTextures(textures);
        gltf.setImages(images);
    }

    private static int alphaDrawOrder(Node node, List<Mesh> meshes, List<Material> materials) {
        if (node == null || node.getMesh() == null) return 0;
        int meshIndex = node.getMesh();
        if (meshIndex < 0 || meshIndex >= meshes.size()) return 0;
        Mesh mesh = meshes.get(meshIndex);
        if (mesh == null || mesh.getPrimitives() == null || mesh.getPrimitives().isEmpty()) return 0;
        Integer materialIndex = mesh.getPrimitives().get(0).getMaterial();
        if (materialIndex == null || materialIndex < 0 || materialIndex >= materials.size()) return 0;
        return GltfMaterialWriter.alphaDrawOrder(materials.get(materialIndex));
    }

    static void write(GlTF gltf, Path path) throws IOException {
        GltfAsset assetModel = new GltfAssetV2(gltf, null);
        new GltfAssetWriter().writeJson(assetModel, path.toFile());
    }
}
