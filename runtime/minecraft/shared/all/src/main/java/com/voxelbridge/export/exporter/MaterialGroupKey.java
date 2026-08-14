package com.voxelbridge.export.exporter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
public final class MaterialGroupKey {
    private MaterialGroupKey() {}

    public static String entity(Entity entity) {
        return "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    public static String blockEntity(BlockEntity blockEntity) {
        return "blockentity:" + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
    }
}
