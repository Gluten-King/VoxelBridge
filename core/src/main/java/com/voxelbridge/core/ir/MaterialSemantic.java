package com.voxelbridge.core.ir;

/**
 * Stable material-key markers shared by capture code and scene consumers.
 *
 * <p>Glyph quads need their texture alpha to shape the character. They are
 * deliberately split from the otherwise opaque entity/block-entity material
 * so ordinary atlas materials can keep the minimal opaque shader contract.</p>
 */
public final class MaterialSemantic {
    private static final String GLYPH_SUFFIX = "__glyph";

    private MaterialSemantic() {}

    public static String glyph(String materialKey) {
        if (materialKey == null || materialKey.isEmpty() || isGlyph(materialKey)) {
            return materialKey;
        }
        return materialKey + GLYPH_SUFFIX;
    }

    public static boolean isGlyph(String materialKey) {
        return materialKey != null && materialKey.endsWith(GLYPH_SUFFIX);
    }
}
