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
    String itemId,
    String fluidId,
    String fluidState,
    boolean fluid,
    int irisRenderType
) {
    public static final QuadSemantic NONE =
        new QuadSemantic(
            null, null, null, null, null, null, null,
            null, null, false, -1
        );

    public QuadSemantic {
        objectClass = emptyToNull(objectClass);
        materialKey = emptyToNull(materialKey);
        blockId = emptyToNull(blockId);
        blockState = emptyToNull(blockState);
        entityType = emptyToNull(entityType);
        blockEntityId = emptyToNull(blockEntityId);
        itemId = emptyToNull(itemId);
        fluidId = emptyToNull(fluidId);
        fluidState = emptyToNull(fluidState);
        if (!fluid) {
            irisRenderType = -1;
        }
    }

    public boolean isEmpty() {
        return objectClass == null
            && materialKey == null
            && blockId == null
            && blockState == null
            && entityType == null
            && blockEntityId == null
            && itemId == null
            && fluidId == null
            && fluidState == null
            && !fluid
            && irisRenderType < 0;
    }

    /**
     * Returns the stable object-type identity used for glTF primitive grouping.
     * Runtime BlockState and FluidState properties are deliberately excluded so
     * visual geometry remains grouped by block/entity type rather than producing
     * duplicate Blender materials for every state combination.
     */
    public QuadSemantic typeIdentity() {
        if (blockState == null && fluidState == null) {
            return this;
        }
        return new QuadSemantic(
            objectClass,
            materialKey,
            blockId,
            null,
            entityType,
            blockEntityId,
            itemId,
            fluidId,
            null,
            fluid,
            irisRenderType
        );
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
            + '\u001f' + field(itemId)
            + '\u001f' + field(fluidId)
            + '\u001f' + field(fluidState)
            + '\u001f' + fluid
            + '\u001f' + irisRenderType;
    }

    private static String field(String value) {
        return value != null ? value : "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
