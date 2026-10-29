package com.voxelbridge.export.exporter.resolve;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for detecting and parsing text/font render types.
 * <p>
 * Platform-specific render type name fragments (e.g. NeoForge's "neoforge_text")
 * must not be hard-coded here. Instead, platform bootstrap code should call
 * {@link #registerHint} at startup to register any additional name fragments
 * that identify text render types on that platform.
 */
public final class TextRenderTypeUtil {

    private TextRenderTypeUtil() {}

    private static final Pattern FONT_PATTERN =
        Pattern.compile("([a-z0-9_.-]+:font/[a-z0-9_./-]+)");

    /** Base fragments always considered text render types. */
    private static final String[] BASE_HINTS = { "text_", "font", "glyph" };

    /** Additional fragments registered by platform bootstrap code. */
    private static final Set<String> EXTRA_HINTS = ConcurrentHashMap.newKeySet();

    /**
     * Registers an additional lower-case name fragment that identifies a text render type.
     * Call this from platform bootstrap (e.g. NeoForge) before any export runs.
     *
     * @param fragment lower-case substring, e.g. {@code "neoforge_text"}
     */
    public static void registerHint(String fragment) {
        if (fragment != null && !fragment.isEmpty()) {
            EXTRA_HINTS.add(fragment.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Returns true if the render type's name indicates it renders font/text glyphs.
     */
    public static boolean isTextRenderType(RenderType renderType) {
        if (renderType == null) return false;
        String name = renderType.toString().toLowerCase(Locale.ROOT);
        for (String hint : BASE_HINTS) {
            if (name.contains(hint)) return true;
        }
        for (String hint : EXTRA_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    /**
     * Returns true if the location looks like a missing/default texture that should
     * trigger font texture fallback resolution.
     */
    public static boolean isDefaultOrMissingLike(Identifier loc) {
        if (loc == null || loc.getPath() == null) return true;
        String p = loc.getPath().toLowerCase(Locale.ROOT);
        // Runtime font atlas pages use valid identifiers such as
        // minecraft:default/0.  "default" describes the font set; it is not a
        // missing-texture marker and must be allowed through to the dynamic
        // texture reader.
        return p.contains("missing")
            || p.endsWith("/white")
            || p.endsWith("white.png");
    }

    /**
     * Attempts to extract a font texture Identifier from the render type's
     * string representation (e.g. {@code minecraft:font/default}).
     *
     * @return the parsed Identifier, or {@code null} if not found
     */
    public static Identifier extractFontTexture(RenderType renderType) {
        if (renderType == null) return null;
        String raw = renderType.toString();
        if (raw == null || raw.isEmpty()) return null;
        Matcher m = FONT_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
        if (m.find()) {
            try {
                return Identifier.tryParse(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
