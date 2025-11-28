package com.voxelbridge.modhandler.yuushya;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.modhandler.ModBlockHandler;
import com.voxelbridge.modhandler.ModHandledQuads;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Yuushya ShowBlock compatibility: generate assembled quads via the mod's own ShowBlockModel.
 * Uses reflection to avoid a hard dependency.
 */
public final class YuushyaShowBlockHandler implements ModBlockHandler {

    private static final String SHOW_BLOCK_ID = "yuushya:showblock";
    private static final String SHOW_BLOCK_ENTITY_CLASS = "com.yuushya.modelling.blockentity.showblock.ShowBlockEntity";
    private static final List<String> MODEL_CLASSES = List.of(
        "com.yuushya.modelling.neoforge.client.ShowBlockModel",
        "com.yuushya.modelling.fabriclike.client.ShowBlockModel",
        "com.yuushya.modelling.blockentity.showblock.ShowBlockModel"
    );

    @Override
    public Optional<ModHandledQuads> handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        BakedModel bakedModel
    ) {
        if (blockEntity == null) return Optional.empty();
        if (!SHOW_BLOCK_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return Optional.empty();
        if (!blockEntity.getClass().getName().equals(SHOW_BLOCK_ENTITY_CLASS)) return Optional.empty();

        try {
            Method getter = blockEntity.getClass().getMethod("getTransformDatas");
            Object dataObj = getter.invoke(blockEntity);
            if (!(dataObj instanceof List<?> transformData)) return Optional.empty();

            Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.SOUTH;

            Object modelInstance = createModel(facing, bakedModel);
            if (modelInstance == null) return Optional.empty();

            Method getQuads = modelInstance.getClass().getMethod(
                "getQuads",
                BlockState.class,
                Direction.class,
                RandomSource.class,
                List.class
            );
            RandomSource rand = RandomSource.create(Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()));
            Object result = getQuads.invoke(modelInstance, state, null, rand, transformData);

            if (!(result instanceof List<?> rawQuads)) return Optional.empty();

            List<BakedQuad> quads = new ArrayList<>();
            for (Object q : rawQuads) {
                if (q instanceof BakedQuad quad) {
                    quads.add(quad);
                }
            }
            if (quads.isEmpty()) return Optional.empty();

            return Optional.of(new ModHandledQuads(quads));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Object createModel(Direction facing, BakedModel backup) {
        for (String className : MODEL_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                try {
                    Constructor<?> ctor = clazz.getConstructor(Direction.class, BakedModel.class);
                    return ctor.newInstance(facing, backup);
                } catch (NoSuchMethodException ignored) {
                    Constructor<?> ctor = clazz.getConstructor(Direction.class);
                    return ctor.newInstance(facing);
                }
            } catch (ClassNotFoundException ignored) {
                // Yuushya not present on this platform; continue
            } catch (Exception ignored) {
                // Constructor failed; continue to next candidate
            }
        }
        return null;
    }
}

