package com.voxelbridge.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Dedicated debug logger for UV3 (overlay colormap) diagnostics.
 * Helps diagnose UV stretching and colormap issues.
 */
public final class UV3DebugLogger {

    // Disabled for release builds to avoid generating large debug logs.
    private static final boolean ENABLED = false;

    private static BufferedWriter writer;
    private static int quadCount = 0;

    private UV3DebugLogger() {}

    public static synchronized void initialize(Path outDir) {
        writer = null;
        quadCount = 0;
        if (ENABLED) {
            try {
                Path logPath = outDir.resolve("uv3-debug.log");
                writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log("=".repeat(100));
                log("UV3 Debug Log - Overlay Colormap Diagnostics");
                log("=".repeat(100));
                log("Format: [Quad#] Sprite | Overlay | OverlaySprite | UV3Source | OverlayColor | UV3 Values");
                log("=".repeat(100));
            } catch (IOException e) {
                System.err.printf("[UV3DebugLogger] Failed to initialize: %s%n", e.getMessage());
            }
        }
    }

    /**
     * Logs UV3 information for a quad.
     * @param spriteKey The base sprite key
     * @param hasOverlay Whether overlay was matched
     * @param overlaySpriteKey The overlay sprite key (if hasOverlay)
     * @param uv3Source Description of where uv3 came from
     * @param uv3 The UV3 array (can be null)
     * @param overlayColor The extracted overlay color (can be 0 if no overlay)
     */
    public static synchronized void logQuadUV3(String spriteKey, boolean hasOverlay, 
                                               String overlaySpriteKey, String uv3Source, 
                                               float[] uv3, int overlayColor) {
        if (ENABLED && writer != null) {
            try {
                quadCount++;
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("[Quad %04d] ", quadCount));
                sb.append(String.format("Sprite: %-40s | ", truncate(spriteKey, 40)));
                sb.append(String.format("Overlay: %-8s | ", hasOverlay ? "YES" : "NO"));
                
                if (hasOverlay && overlaySpriteKey != null) {
                    sb.append(String.format("OverlaySprite: %-40s | ", truncate(overlaySpriteKey, 40)));
                } else {
                    sb.append(String.format("OverlaySprite: %-40s | ", "-"));
                }
                
                sb.append(String.format("UV3Source: %-25s | ", truncate(uv3Source, 25)));
                
                if (hasOverlay) {
                    sb.append(String.format("OverlayColor: #%08X | ", overlayColor));
                } else {
                    sb.append("OverlayColor: N/A        | ");
                }
                
                if (uv3 != null && uv3.length >= 8) {
                    // Log all 4 vertices (8 values)
                    sb.append(String.format("UV3: [%.4f,%.4f] [%.4f,%.4f] [%.4f,%.4f] [%.4f,%.4f]", 
                        uv3[0], uv3[1], uv3[2], uv3[3], 
                        uv3[4], uv3[5], uv3[6], uv3[7]));
                } else if (uv3 != null) {
                    sb.append(String.format("UV3: %d values (expected 8)", uv3.length));
                } else {
                    sb.append("UV3: NULL (!!!)");
                }
                
                writer.write(sb.toString());
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[UV3DebugLogger] Failed to write log: %s%n", e.getMessage());
            }
        }
    }

    private static void log(String message) {
        if (ENABLED && writer != null) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.printf("[UV3DebugLogger] Failed to write log: %s%n", e.getMessage());
            }
        }
    }

    public static synchronized void close() {
        if (ENABLED && writer != null) {
            try {
                log("=".repeat(100));
                log(String.format("Total quads logged: %d", quadCount));
                log("=".repeat(100));
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            } finally {
                writer = null;
                quadCount = 0;
            }
        } else {
            writer = null;
            quadCount = 0;
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 3) + "...";
    }
}

