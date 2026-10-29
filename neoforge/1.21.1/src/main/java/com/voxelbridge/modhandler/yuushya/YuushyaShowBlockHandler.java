package com.voxelbridge.modhandler.yuushya;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.modhandler.ModBlockHandler;
import com.voxelbridge.modhandler.ModHandledQuads;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Yuushya ShowBlock compatibility: generate assembled quads via the mod's own ShowBlockModel.
 * Uses reflection to avoid a hard dependency. Reflective handles are resolved once against the
 * first ShowBlockEntity seen; the returned quads are null-side geometry and must not be face-culled.
 */
public final class YuushyaShowBlockHandler implements ModBlockHandler {

    private static final String SHOW_BLOCK_ID = "yuushya:showblock";
    private static final String SHOW_BLOCK_ENTITY_CLASS = "com.yuushya.modelling.blockentity.showblock.ShowBlockEntity";
    private static final List<String> MODEL_CLASSES = List.of(
        // Yuushya Modelling 2.4.x (NeoForge 1.21.1)
        "com.yuushya.modelling.client.NeoShowBlockModel",
        // Older Architectury layouts
        "com.yuushya.modelling.neoforge.client.NeoShowBlockModel",
        "com.yuushya.modelling.neoforge.client.ShowBlockModel",
        "com.yuushya.modelling.fabriclike.client.ShowBlockModel",
        "com.yuushya.modelling.blockentity.showblock.ShowBlockModel"
    );
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean SUCCESS_REPORTED = new AtomicBoolean();

    private volatile Bindings bindings;
    private volatile boolean unavailable;

    @Override
    public Optional<ModHandledQuads> handle(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        Object bakedModel
    ) {
        if (blockEntity == null) return Optional.empty();
        if (unavailable || !blockEntity.getClass().getName().equals(SHOW_BLOCK_ENTITY_CLASS)) return Optional.empty();
        if (!SHOW_BLOCK_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return Optional.empty();

        try {
            Bindings resolved = bindings(blockEntity.getClass());
            Object dataObj = resolved.transformDataGetter().invoke(blockEntity);
            if (!(dataObj instanceof List<?> transformData) || transformData.isEmpty()) return Optional.empty();

            Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.SOUTH;

            Object modelInstance = resolved.newModel(facing, bakedModel);
            RandomSource rand = RandomSource.create(Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()));
            Object result = resolved.getQuads().invoke(modelInstance, state, null, rand, transformData);
            if (!(result instanceof List<?> rawQuads)) return Optional.empty();

            List<BakedQuad> quads = new ArrayList<>(rawQuads.size());
            for (Object q : rawQuads) {
                if (q instanceof BakedQuad quad) {
                    quads.add(quad);
                }
            }
            if (quads.isEmpty()) return Optional.empty();

            if (SUCCESS_REPORTED.compareAndSet(false, true)) {
                VoxelBridgeLogger.info(
                    LogModule.EXPORT,
                    "[Yuushya] ShowBlock model compatibility active via " + resolved.modelClassName()
                        + " (" + quads.size() + " quads in first model)"
                );
            }

            return Optional.of(new ModHandledQuads(quads));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            if (bindings == null) {
                // The installed Yuushya API does not match any known layout; stop probing per block.
                unavailable = true;
            }
            if (FAILURE_REPORTED.compareAndSet(false, true)) {
                VoxelBridgeLogger.warn(
                    LogModule.EXPORT,
                    "[Yuushya] ShowBlock compatibility failed at " + pos.toShortString()
                        + "; falling back to the baked block model: " + cause
                );
            }
            return Optional.empty();
        }
    }

    @Override
    public boolean requiresBlockEntity() {
        return true;
    }

    private Bindings bindings(Class<?> blockEntityClass) throws ReflectiveOperationException {
        Bindings current = bindings;
        if (current == null) {
            synchronized (this) {
                current = bindings;
                if (current == null) {
                    current = Bindings.resolve(blockEntityClass);
                    bindings = current;
                }
            }
        }
        return current;
    }

    private record Bindings(
        Method transformDataGetter,
        String modelClassName,
        Constructor<?> facingCtor,
        Constructor<?> facingBackupCtor,
        Method getQuads
    ) {
        static Bindings resolve(Class<?> blockEntityClass) throws ReflectiveOperationException {
            Method getter = resolveTransformDataGetter(blockEntityClass);
            ClassLoader loader = blockEntityClass.getClassLoader();
            for (String className : MODEL_CLASSES) {
                Class<?> modelClass;
                try {
                    modelClass = Class.forName(className, true, loader);
                } catch (ClassNotFoundException absent) {
                    continue;
                }
                Constructor<?> facingCtor = modelClass.getConstructor(Direction.class);
                Constructor<?> facingBackupCtor = null;
                try {
                    facingBackupCtor = modelClass.getConstructor(Direction.class, BakedModel.class);
                } catch (NoSuchMethodException ignored) {
                    // Older builds only accept the facing.
                }
                Method getQuads = modelClass.getMethod(
                    "getQuads", BlockState.class, Direction.class, RandomSource.class, List.class);
                return new Bindings(getter, className, facingCtor, facingBackupCtor, getQuads);
            }
            throw new ClassNotFoundException("No Yuushya ShowBlockModel class found among " + MODEL_CLASSES);
        }

        private static Method resolveTransformDataGetter(Class<?> blockEntityClass) throws NoSuchMethodException {
            try {
                return blockEntityClass.getMethod("getTransformData");
            } catch (NoSuchMethodException currentApiMissing) {
                // Compatibility with older Yuushya builds which used the plural name.
                return blockEntityClass.getMethod("getTransformDatas");
            }
        }

        Object newModel(Direction facing, Object backup) throws ReflectiveOperationException {
            if (facingBackupCtor != null && backup instanceof BakedModel) {
                return facingBackupCtor.newInstance(facing, backup);
            }
            return facingCtor.newInstance(facing);
        }
    }
}
