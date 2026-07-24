package com.voxelbridge.export.semantic;

import com.voxelbridge.core.ir.QuadSemantic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.Map;

/**
 * Converts live Minecraft objects into the stable scene identity contract.
 */
public final class MinecraftQuadSemantic {
    private MinecraftQuadSemantic() {
    }

    public static QuadSemantic terrain(BlockState state) {
        String blockId = blockId(state);
        return new QuadSemantic(
            "terrain",
            blockId,
            blockId,
            canonicalBlockState(state),
            null,
            null,
            null,
            null,
            null,
            false,
            -1
        );
    }

    public static QuadSemantic fluid(BlockState hostState, FluidState fluidState) {
        String blockId = blockId(hostState);
        String fluidId = fluidId(fluidState);
        return new QuadSemantic(
            "terrain",
            blockId != null ? blockId : fluidId,
            blockId,
            canonicalBlockState(hostState),
            null,
            null,
            null,
            fluidId,
            canonicalFluidState(fluidState),
            true,
            1
        );
    }

    public static QuadSemantic entity(Entity entity) {
        String entityType = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        String itemId = itemId(entityItem(entity));
        return new QuadSemantic(
            "entity",
            entityType,
            null,
            null,
            entityType,
            null,
            itemId,
            null,
            null,
            false,
            -1
        );
    }

    public static QuadSemantic blockEntity(BlockEntity blockEntity) {
        String blockEntityId =
            String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
        BlockState state = blockEntity.getBlockState();
        String blockId = blockId(state);
        return new QuadSemantic(
            "block_entity",
            blockId != null ? blockId : blockEntityId,
            blockId,
            canonicalBlockState(state),
            null,
            blockEntityId,
            null,
            null,
            null,
            false,
            -1
        );
    }

    /**
     * Iris at_midBlock: offset from each terrain vertex to the block center,
     * in 1/64 block units. W stores the emitting block's own light level.
     */
    public static float[] atMidBlock(
        BlockPos pos,
        double offsetX,
        double offsetY,
        double offsetZ,
        float[] positions,
        int emission
    ) {
        if (pos == null || positions == null || positions.length < 12) {
            return null;
        }
        float centerX = (float) (pos.getX() + 0.5 + offsetX);
        float centerY = (float) (pos.getY() + 0.5 + offsetY);
        float centerZ = (float) (pos.getZ() + 0.5 + offsetZ);
        float blockEmission = Math.max(0, Math.min(15, emission));
        float[] result = new float[16];
        for (int vertex = 0; vertex < 4; vertex++) {
            int positionBase = vertex * 3;
            int resultBase = vertex * 4;
            result[resultBase] = (centerX - positions[positionBase]) * 64.0f;
            result[resultBase + 1] = (centerY - positions[positionBase + 1]) * 64.0f;
            result[resultBase + 2] = (centerZ - positions[positionBase + 2]) * 64.0f;
            result[resultBase + 3] = blockEmission;
        }
        return result;
    }

    public static String canonicalBlockState(BlockState state) {
        String id = blockId(state);
        if (id == null) {
            return null;
        }
        if (state.getValues().isEmpty()) {
            return id;
        }
        StringBuilder result = new StringBuilder(id).append('[');
        state.getValues().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .forEachOrdered(entry -> {
                if (result.charAt(result.length() - 1) != '[') {
                    result.append(',');
                }
                result.append(entry.getKey().getName())
                    .append('=')
                    .append(propertyValue(entry));
            });
        return result.append(']').toString();
    }

    public static String canonicalFluidState(FluidState state) {
        String id = fluidId(state);
        if (id == null) {
            return null;
        }
        if (state.getValues().isEmpty()) {
            return id;
        }
        StringBuilder result = new StringBuilder(id).append('[');
        state.getValues().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .forEachOrdered(entry -> appendProperty(result, entry));
        return result.append(']').toString();
    }

    private static String blockId(BlockState state) {
        if (state == null) {
            return null;
        }
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private static String fluidId(FluidState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return String.valueOf(BuiltInRegistries.FLUID.getKey(state.getType()));
    }

    private static void appendProperty(
        StringBuilder result,
        Map.Entry<Property<?>, Comparable<?>> entry
    ) {
        if (result.charAt(result.length() - 1) != '[') {
            result.append(',');
        }
        result.append(entry.getKey().getName())
            .append('=')
            .append(propertyValue(entry));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(Map.Entry<Property<?>, Comparable<?>> entry) {
        Property property = entry.getKey();
        return property.getName(entry.getValue());
    }

    private static ItemStack entityItem(Entity entity) {
        if (entity instanceof ItemEntity itemEntity) {
            return itemEntity.getItem();
        }
        if (entity instanceof ItemFrame frame) {
            return frame.getItem();
        }
        // ItemDisplay has mapping-sensitive nested class names between supported
        // Minecraft versions. Resolve its public accessor without hard-linking.
        if ("item_display".equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())) {
            try {
                Method method = entity.getClass().getMethod("getItemStack");
                Object result = method.invoke(entity);
                if (result instanceof ItemStack stack) {
                    return stack;
                }
            } catch (ReflectiveOperationException ignored) {
                // Keep the authoritative entity type even if a mapping lacks the accessor.
            }
        }
        return ItemStack.EMPTY;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
