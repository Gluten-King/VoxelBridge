package com.voxelbridge.export.exporter;

import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects overlay quads from stacked quads sharing the same geometry.
 */
final class OverlayClassifier {
    private OverlayClassifier() {}

    static boolean isContinuitySprite(String spriteKey) {
        if (spriteKey == null) return false;
        return spriteKey.toLowerCase(Locale.ROOT).contains("continuity");
    }

    static void classifyByPosition(List<QuadData> quads,
                                   String[] spriteKeys,
                                   VertexExtractor.VertexData[] vertexCache,
                                   boolean[] isOverlay,
                                   BlockState state,
                                   BlockPos pos,
                                   String blockKey,
                                   Vec3 randomOffset,
                                   double offsetX,
                                   double offsetY,
                                   double offsetZ,
                                   OverlayManager overlayManager) {
        record QuadEntry(int index, QuadData quad, String spriteKey, long posHash,
                         float uMin, float uMax, float vMin, float vMax,
                         boolean overlayCandidate, boolean hilight) {}

        Map<Long, List<QuadEntry>> groups = new HashMap<>();

        for (int i = 0; i < quads.size(); i++) {
            QuadData quad = quads.get(i);
            String spriteKey = spriteKeys[i];
            if (quad == null || spriteKey == null) continue;
            var vertexData = (vertexCache != null && i < vertexCache.length) ? vertexCache[i] : null;
            if (vertexData == null) {
                var sprite = quad.sprite();
                if (sprite == null) continue;
                vertexData = VertexExtractor.extractFromQuad(
                    quad, pos, sprite, offsetX, offsetY, offsetZ, randomOffset
                );
                if (vertexCache != null && i < vertexCache.length) {
                    vertexCache[i] = vertexData;
                }
            }
            float[] uv = vertexData.uvs();
            float uMin = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
            float uMax = Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]));
            float vMin = Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]));
            float vMax = Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7]));

            long posHash = computePositionHash(vertexData.positions());
            boolean overlayCandidate = isOverlayCandidateSprite(spriteKey);
            boolean hilight = isHilightOverlay(spriteKey);
            groups.computeIfAbsent(posHash, k -> new ArrayList<>())
                .add(new QuadEntry(i, quad, spriteKey, posHash, uMin, uMax, vMin, vMax, overlayCandidate, hilight));
        }

        final float UV_EPS = 1e-4f;
        for (List<QuadEntry> group : groups.values()) {
            if (group.size() < 2) continue;
            boolean hasCandidate = group.stream().anyMatch(q -> q.overlayCandidate);
            if (!hasCandidate) continue;
            boolean hasCtmCandidate = group.stream().anyMatch(q -> isCtmCandidateSprite(q.spriteKey));

            QuadEntry first = group.get(0);
            float du = first.uMax - first.uMin;
            float dv = first.vMax - first.vMin;
            boolean sameShape = group.stream().allMatch(q ->
                Math.abs((q.uMax - q.uMin) - du) < UV_EPS &&
                Math.abs((q.vMax - q.vMin) - dv) < UV_EPS);
            if (!sameShape) continue;

            int minIndex = group.stream().mapToInt(QuadEntry::index).min().orElse(Integer.MAX_VALUE);
            group.sort(Comparator.comparingInt(QuadEntry::index));

            String baseMaterialKey = hasCtmCandidate
                ? blockKey
                : (spriteKeys[minIndex] != null ? spriteKeys[minIndex] : blockKey);

            for (QuadEntry entry : group) {
                if (entry.index == minIndex) continue;
                if (entry.hilight) {
                    overlayManager.cacheHilight(baseMaterialKey, state, pos, entry.quad, randomOffset, entry.spriteKey);
                } else {
                    overlayManager.cacheOverlay(baseMaterialKey, state, pos, entry.quad, randomOffset, entry.spriteKey);
                }
                isOverlay[entry.index] = true;
            }
        }
    }

    private static boolean isOverlayCandidateSprite(String spriteKey) {
        if (spriteKey == null) return false;
        String lower = spriteKey.toLowerCase(Locale.ROOT);
        return lower.contains("_overlay")
            || lower.contains("_hilight")
            || lower.contains("/ctm/")
            || lower.contains("ctm/")
            || lower.contains("continuity");
    }

    private static boolean isHilightOverlay(String spriteKey) {
        if (spriteKey == null) return false;
        return spriteKey.toLowerCase(Locale.ROOT).contains("_hilight");
    }

    private static boolean isCtmCandidateSprite(String spriteKey) {
        if (spriteKey == null) return false;
        String lower = spriteKey.toLowerCase(Locale.ROOT);
        return lower.contains("/ctm/") || lower.contains("ctm/") || lower.contains("continuity");
    }

    private static long computePositionHash(float[] positions) {
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> {
            int ia = a * 3;
            int ib = b * 3;
            int cmpX = Float.compare(positions[ia], positions[ib]);
            if (cmpX != 0) return cmpX;
            int cmpY = Float.compare(positions[ia + 1], positions[ib + 1]);
            if (cmpY != 0) return cmpY;
            return Float.compare(positions[ia + 2], positions[ib + 2]);
        });
        long hash = 1125899906842597L;
        for (int idx : order) {
            int pi = idx * 3;
            hash = 31 * hash + Math.round(positions[pi] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 1] * 100f);
            hash = 31 * hash + Math.round(positions[pi + 2] * 100f);
        }
        return hash;
    }
}
