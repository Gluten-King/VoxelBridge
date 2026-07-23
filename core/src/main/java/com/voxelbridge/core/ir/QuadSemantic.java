package com.voxelbridge.core.ir;

/**
 * Stable Minecraft identity attached to a rendered quad.
 *
 * <p>The visual material remains separate: shader-pack property files match
 * these registry/state fields, while atlas and per-sprite material grouping
 * may change independently.</p>
 */
public record QuadSemantic(
    String objectClass,
    String materialKey,
    String blockId,
    String blockState,
    String entityType,
    String blockEntityId,
    String itemId
) {
    public static final QuadSemantic NONE =
        new QuadSemantic(null, null, null, null, null, null, null);

    public QuadSemantic {
        objectClass = emptyToNull(objectClass);
        materialKey = emptyToNull(materialKey);
        blockId = emptyToNull(blockId);
        blockState = emptyToNull(blockState);
        entityType = emptyToNull(entityType);
        blockEntityId = emptyToNull(blockEntityId);
        itemId = emptyToNull(itemId);
    }

    public boolean isEmpty() {
        return objectClass == null
            && materialKey == null
            && blockId == null
            && blockState == null
            && entityType == null
            && blockEntityId == null
            && itemId == null;
    }

    /**
     * Collision-free internal grouping key. Registry and state strings cannot
     * contain the ASCII unit separator used here.
     */
    public String stableKey() {
        return field(objectClass)
            + '\u001f' + field(materialKey)
            + '\u001f' + field(blockId)
            + '\u001f' + field(blockState)
            + '\u001f' + field(entityType)
            + '\u001f' + field(blockEntityId)
            + '\u001f' + field(itemId);
    }

    private static String field(String value) {
        return value != null ? value : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
