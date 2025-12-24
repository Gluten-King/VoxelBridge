package com.voxelbridge.export.texture;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports Minecraft-compatible .mcmeta animation metadata files.
 * Generates JSON files alongside exported frame sequences.
 */
public final class AnimationMetadataExporter {

    private AnimationMetadataExporter() {}

    /**
     * Exports animation metadata as a .mcmeta JSON file.
     *
     * @param outputDir Directory where the animation.mcmeta file should be written
     * @param metadata Animation metadata to export
     * @param frameCount Total number of frames (for validation)
     */
    public static void exportMetadata(Path outputDir, AnimationMetadata metadata, int frameCount) {
        if (metadata == null || outputDir == null) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, "[AnimationExport][WARN] Cannot export metadata: null input");
            return;
        }

        try {
            Path metaFile = outputDir.resolve("animation.mcmeta");
            String json = generateMcmetaJson(metadata, frameCount);

            Files.writeString(metaFile, json, StandardCharsets.UTF_8);
            VoxelBridgeLogger.info(LogModule.ANIMATION, String.format("[AnimationExport] Wrote metadata: %s (%d bytes)",
                metaFile.getFileName(), json.length()));

        } catch (IOException e) {
            VoxelBridgeLogger.error(LogModule.ANIMATION, String.format("[AnimationExport][ERROR] Failed to write metadata: %s",
                e.getMessage()));
        }
    }

    /**
     * Generates Minecraft-compatible .mcmeta JSON content.
     *
     * Format reference: https://minecraft.wiki/w/Resource_Pack#Animation
     *
     * Example output:
     * {
     *   "animation": {
     *     "frametime": 4,
     *     "interpolate": true,
     *     "frames": [0, 1, 2, {"index": 3, "time": 10}, 2, 1]
     *   }
     * }
     */
    private static String generateMcmetaJson(AnimationMetadata metadata, int frameCount) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"animation\": {\n");

        // Default frame time (in ticks, 20 ticks = 1 second)
        json.append("    \"frametime\": ").append(metadata.defaultFrameTime());

        // Interpolation flag
        if (metadata.interpolate()) {
            json.append(",\n    \"interpolate\": true");
        }

        // Frame dimensions (optional, for non-square frames)
        if (metadata.frameWidth() > 0 && metadata.frameHeight() > 0) {
            json.append(",\n    \"width\": ").append(metadata.frameWidth());
            json.append(",\n    \"height\": ").append(metadata.frameHeight());
        }

        // Custom frame order and timing
        if (metadata.hasCustomFrameOrder()) {
            json.append(",\n    \"frames\": [\n");
            List<AnimationMetadata.FrameTiming> timings = metadata.frameTimings();

            for (int i = 0; i < timings.size(); i++) {
                AnimationMetadata.FrameTiming timing = timings.get(i);
                json.append("      ");

                // If frame uses default timing, just output the index
                // Otherwise, output as {"index": N, "time": T}
                if (timing.time() == metadata.defaultFrameTime()) {
                    json.append(timing.index());
                } else {
                    json.append("{\"index\": ").append(timing.index());
                    json.append(", \"time\": ").append(timing.time()).append("}");
                }

                if (i < timings.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
            }

            json.append("    ]");
        }

        json.append("\n  }\n");
        json.append("}\n");

        return json.toString();
    }

    /**
     * Exports individual frame metadata files (frame_000.png.mcmeta, etc.).
     * This is for advanced use cases where each frame needs separate metadata.
     * Most cases should use exportMetadata() instead.
     *
     * @param frameFile Path to the frame PNG file
     * @param frameIndex Index of this frame in the animation
     * @param metadata Animation metadata
     */
    public static void exportFrameMetadata(Path frameFile, int frameIndex, AnimationMetadata metadata) {
        if (frameFile == null || metadata == null) {
            return;
        }

        try {
            Path metaFile = Path.of(frameFile.toString() + ".mcmeta");

            // Simple frame metadata (just marks it as part of animation)
            String json = "{\n" +
                         "  \"animation\": {\n" +
                         "    \"frametime\": " + metadata.getFrameTime(frameIndex) + "\n" +
                         "  }\n" +
                         "}\n";

            Files.writeString(metaFile, json, StandardCharsets.UTF_8);

        } catch (IOException e) {
            VoxelBridgeLogger.warn(LogModule.ANIMATION, String.format("[AnimationExport][WARN] Failed to write frame metadata for %s: %s",
                frameFile.getFileName(), e.getMessage()));
        }
    }

    /**
     * Validates that metadata is compatible with the exported frame count.
     *
     * @param metadata Animation metadata
     * @param frameCount Number of frames exported
     * @return true if metadata is valid for the given frame count
     */
    public static boolean validateMetadata(AnimationMetadata metadata, int frameCount) {
        if (metadata == null || frameCount <= 0) {
            return false;
        }

        // Check if custom frame order references valid indices
        if (metadata.hasCustomFrameOrder()) {
            for (AnimationMetadata.FrameTiming timing : metadata.frameTimings()) {
                if (timing.index() < 0 || timing.index() >= frameCount) {
                    VoxelBridgeLogger.info(LogModule.ANIMATION, String.format(
                        "[AnimationExport][WARN] Frame timing references invalid index %d (max: %d)",
                        timing.index(), frameCount - 1
                    ));
                    return false;
                }
            }
        }

        return true;
    }
}




