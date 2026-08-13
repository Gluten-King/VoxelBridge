package com.voxelbridge.verification;

import java.util.List;

public record GoldenSnapshot(
        int schemaVersion,
        String scenario,
        String minecraftVersion,
        String scenarioHash,
        int sceneCount,
        int nodeCount,
        int meshCount,
        int primitiveCount,
        long vertexCount,
        long triangleCount,
        int imageReferenceCount,
        List<Double> boundsMin,
        List<Double> boundsMax,
        String geometryHash,
        String appearanceHash,
        List<AssertionSnapshot> assertions,
        List<MaterialSnapshot> materials,
        List<ImageSnapshot> images) {

    public record AssertionSnapshot(
            String id,
            String type,
            int materialCount,
            int primitiveCount,
            long vertexCount,
            long triangleCount) {}

    public record MaterialSnapshot(
            String name,
            int primitiveCount,
            long vertexCount,
            long triangleCount,
            String geometryHash,
            String appearanceHash,
            List<String> textureRgbaHashes) {}

    public record ImageSnapshot(
            String id,
            int width,
            int height,
            String rgbaHash) {}
}
