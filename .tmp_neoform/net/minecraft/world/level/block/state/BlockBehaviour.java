package net.minecraft.world.level.block.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder.Instance;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.flag.FeatureElement;
import net.minecraft.world.flag.FeatureFlag;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class BlockBehaviour implements FeatureElement {
    protected static final Direction[] UPDATE_SHAPE_ORDER = new Direction[]{
        Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP
    };
    protected final boolean hasCollision;
    protected final float explosionResistance;
    /**
     * Whether this blocks receives random ticks
     */
    protected final boolean isRandomlyTicking;
    protected final SoundType soundType;
    /**
     * Determines how much velocity is maintained while moving on top of this block
     */
    protected final float friction;
    protected final float speedFactor;
    protected final float jumpFactor;
    protected final boolean dynamicShape;
    protected final FeatureFlagSet requiredFeatures;
    protected final BlockBehaviour.Properties properties;
    @Nullable
    protected ResourceKey<LootTable> drops;

    public BlockBehaviour(BlockBehaviour.Properties properties) {
        this.hasCollision = properties.hasCollision;
        this.drops = properties.drops;
        this.explosionResistance = properties.explosionResistance;
        this.isRandomlyTicking = properties.isRandomlyTicking;
        this.soundType = properties.soundType;
        this.friction = properties.friction;
        this.speedFactor = properties.speedFactor;
        this.jumpFactor = properties.jumpFactor;
        this.dynamicShape = properties.dynamicShape;
        this.requiredFeatures = properties.requiredFeatures;
        this.properties = properties;
        final ResourceKey<LootTable> lootTableCache = properties.drops;
        if (lootTableCache != null) {
            this.lootTableSupplier = () -> lootTableCache;
        } else if (properties.lootTableSupplier != null) {
            this.lootTableSupplier = properties.lootTableSupplier;
        } else {
            this.lootTableSupplier = () -> {
                ResourceLocation resourcelocation = BuiltInRegistries.BLOCK.getKey(this.asBlock());
                return ResourceKey.create(Registries.LOOT_TABLE, resourcelocation.withPrefix("blocks/"));
            };
        }
    }

    public BlockBehaviour.Properties properties() {
        return this.properties;
    }

    protected abstract MapCodec<? extends Block> codec();

    public static <B extends Block> RecordCodecBuilder<B, BlockBehaviour.Properties> propertiesCodec() {
        return BlockBehaviour.Properties.CODEC.fieldOf("properties").forGetter(BlockBehaviour::properties);
    }

    public static <B extends Block> MapCodec<B> simpleCodec(Function<BlockBehaviour.Properties, B> factory) {
        return RecordCodecBuilder.mapCodec(p_304392_ -> p_304392_.group(propertiesCodec()).apply(p_304392_, factory));
    }

    /**
     * Updates the shapes of indirect neighbors of this block. This method is analogous to
     * {@link net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase#updateNeighbourShapes}
     * but where that method affects the 6 direct neighbors of this block, this method affects
     * the indirect neighbors, if any.
     *
     * <p>
     * Currently the only implementation of this method is {@link net.minecraft.world.level.block.RedStoneWireBlock#updateIndirectNeighbourShapes}
     * where it is used to validate diagonal connections of redstone wire blocks.
     */
    protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
    }

    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        switch (pathComputationType) {
            case LAND:
                return !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            case WATER:
                return state.getFluidState().is(FluidTags.WATER);
            case AIR:
                return !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            default:
                return false;
        }
    }

    /**
     * Update the provided state given the provided neighbor direction and neighbor state, returning a new state.
     * For example, fences make their connections to the passed in state if possible, and wet concrete powder immediately returns its solidified counterpart.
     * Note that this method should ideally consider only the specific direction passed in.
     */
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state;
    }

    protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return false;
    }

    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        DebugPackets.sendNeighborsUpdatePacket(level, pos);
    }

    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
    }

    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock())) {
            level.removeBlockEntity(pos);
        }
    }

    protected void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> dropConsumer) {
        if (!state.isAir() && explosion.getBlockInteraction() != Explosion.BlockInteraction.TRIGGER_BLOCK) {
            Block block = state.getBlock();
            boolean flag = explosion.getIndirectSourceEntity() instanceof Player;
            if (state.canDropFromExplosion(level, pos, explosion) && level instanceof ServerLevel serverlevel) {
                BlockEntity blockentity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                LootParams.Builder lootparams$builder = new LootParams.Builder(serverlevel)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockentity)
                    .withOptionalParameter(LootContextParams.THIS_ENTITY, explosion.getDirectSourceEntity());
                if (explosion.getBlockInteraction() == Explosion.BlockInteraction.DESTROY_WITH_DECAY) {
                    lootparams$builder.withParameter(LootContextParams.EXPLOSION_RADIUS, explosion.radius());
                }

                state.spawnAfterBreak(serverlevel, pos, ItemStack.EMPTY, flag);
                state.getDrops(lootparams$builder).forEach(p_311752_ -> dropConsumer.accept(p_311752_, pos));
            }

            state.onBlockExploded(level, pos, explosion);
        }
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return InteractionResult.PASS;
    }

    protected ItemInteractionResult useItemOn(
        ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
    ) {
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Called on server when {@link net.minecraft.world.level.Level#blockEvent} is called. If server returns true, then also called on the client. On the Server, this may perform additional changes to the world, like pistons replacing the block with an extended base. On the client, the update may involve replacing block entities or effects such as sounds or particles
     */
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        return false;
    }

    /**
     * The type of render function called. MODEL for mixed tesr and static model, MODELBLOCK_ANIMATED for TESR-only, LIQUID for vanilla liquids, INVISIBLE to skip all rendering
     */
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    /**
     * Returns whether this block is capable of emitting redstone signals.
     *
     */
    protected boolean isSignalSource(BlockState state) {
        return false;
    }

    protected FluidState getFluidState(BlockState state) {
        return Fluids.EMPTY.defaultFluidState();
    }

    protected boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }

    protected float getMaxHorizontalOffset() {
        return 0.25F;
    }

    protected float getMaxVerticalOffset() {
        return 0.2F;
    }

    @Override
    public FeatureFlagSet requiredFeatures() {
        return this.requiredFeatures;
    }

    /**
     * Returns the blockstate with the given rotation from the passed blockstate. If inapplicable, returns the passed blockstate.
     */
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state;
    }

    /**
     * Returns the blockstate with the given mirror of the passed blockstate. If inapplicable, returns the passed blockstate.
     */
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    protected boolean canBeReplaced(BlockState state, BlockPlaceContext useContext) {
        return state.canBeReplaced() && (useContext.getItemInHand().isEmpty() || !useContext.getItemInHand().is(this.asItem()));
    }

    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return state.canBeReplaced() || !state.isSolid();
    }

    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ResourceKey<LootTable> resourcekey = this.getLootTable();
        if (resourcekey == BuiltInLootTables.EMPTY) {
            return Collections.emptyList();
        } else {
            LootParams lootparams = params.withParameter(LootContextParams.BLOCK_STATE, state).create(LootContextParamSets.BLOCK);
            ServerLevel serverlevel = lootparams.getLevel();
            LootTable loottable = serverlevel.getServer().reloadableRegistries().getLootTable(resourcekey);
            return loottable.getRandomItems(lootparams);
        }
    }

    /**
     * Return a random long to be passed to {@link net.minecraft.client.resources.model.BakedModel#getQuads}, used for random model rotations
     */
    protected long getSeed(BlockState state, BlockPos pos) {
        return Mth.getSeed(pos);
    }

    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getShape(level, pos);
    }

    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return this.getCollisionShape(state, level, pos, CollisionContext.empty());
    }

    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isSolidRender(level, pos)) {
            return level.getMaxLightLevel();
        } else {
            return state.propagatesSkylightDown(level, pos) ? 0 : 1;
        }
    }

    @Nullable
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return null;
    }

    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return true;
    }

    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isCollisionShapeFullBlock(level, pos) ? 0.2F : 1.0F;
    }

    /**
     * Returns the analog signal this block emits. This is the signal a comparator can read from it.
     *
     */
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return 0;
    }

    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.hasCollision ? state.getShape(level, pos) : Shapes.empty();
    }

    protected boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return Block.isShapeFullBlock(state.getCollisionShape(level, pos));
    }

    protected boolean isOcclusionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return Block.isShapeFullBlock(state.getOcclusionShape(level, pos));
    }

    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.getCollisionShape(state, level, pos, context);
    }

    /**
     * Performs a random tick on a block.
     */
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    }

    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    }

    /**
     * Get the hardness of this Block relative to the ability of the given player
     */
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float f = state.getDestroySpeed(level, pos);
        if (f == -1.0F) {
            return 0.0F;
        } else {
            int i = net.neoforged.neoforge.event.EventHooks.doPlayerHarvestCheck(player, state, level, pos) ? 30 : 100;
            return player.getDigSpeed(state, pos) / f / (float)i;
        }
    }

    /**
     * Perform side-effects from block dropping, such as creating silverfish
     */
    protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack, boolean dropExperience) {
    }

    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
    }

    /**
     * Returns the signal this block emits in the given direction.
     *
     * <p>
     * NOTE: directions in redstone signal related methods are backwards, so this method
     * checks for the signal emitted in the <i>opposite</i> direction of the one given.
     *
     */
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 0;
    }

    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
    }

    /**
     * Returns the direct signal this block emits in the given direction.
     *
     * <p>
     * NOTE: directions in redstone signal related methods are backwards, so this method
     * checks for the signal emitted in the <i>opposite</i> direction of the one given.
     *
     */
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 0;
    }

    public final ResourceKey<LootTable> getLootTable() {
        if (this.drops == null) {
            this.drops = this.lootTableSupplier.get();
        }

        return this.drops;
    }

    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
    }

    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !Block.isShapeFullBlock(state.getShape(level, pos)) && state.getFluidState().isEmpty();
    }

    protected boolean isRandomlyTicking(BlockState state) {
        return this.isRandomlyTicking;
    }

    @Deprecated //Forge: Use more sensitive version {@link IForgeBlockState#getSoundType(IWorldReader, BlockPos, Entity) }
    protected SoundType getSoundType(BlockState state) {
        return this.soundType;
    }

    public abstract Item asItem();

    protected abstract Block asBlock();

    public MapColor defaultMapColor() {
        return this.properties.mapColor.apply(this.asBlock().defaultBlockState());
    }

    public float defaultDestroyTime() {
        return this.properties.destroyTime;
    }

    protected boolean isAir(BlockState state) {
        return ((BlockStateBase)state).isAir;
    }

    // Neo: Holds the loot table for this block's drops. Used for getLootTable method.
    private final java.util.function.Supplier<ResourceKey<LootTable>> lootTableSupplier;

    public abstract static class BlockStateBase extends StateHolder<Block, BlockState> {
        private final int lightEmission;
        private final boolean useShapeForLightOcclusion;
        private final boolean isAir;
        private final boolean ignitedByLava;
        @Deprecated
        private final boolean liquid;
        @Deprecated
        private boolean legacySolid;
        private final PushReaction pushReaction;
        private final MapColor mapColor;
        private final float destroySpeed;
        private final boolean requiresCorrectToolForDrops;
        private final boolean canOcclude;
        private final BlockBehaviour.StatePredicate isRedstoneConductor;
        private final BlockBehaviour.StatePredicate isSuffocating;
        private final BlockBehaviour.StatePredicate isViewBlocking;
        private final BlockBehaviour.StatePredicate hasPostProcess;
        private final BlockBehaviour.StatePredicate emissiveRendering;
        @Nullable
        private final BlockBehaviour.OffsetFunction offsetFunction;
        private final boolean spawnTerrainParticles;
        private final NoteBlockInstrument instrument;
        private final boolean replaceable;
        @Nullable
        protected BlockBehaviour.BlockStateBase.Cache cache;
        private FluidState fluidState = Fluids.EMPTY.defaultFluidState();
        private boolean isRandomlyTicking;

        protected BlockStateBase(Block owner, Reference2ObjectArrayMap<Property<?>, Comparable<?>> values, MapCodec<BlockState> propertiesCodec) {
            super(owner, values, propertiesCodec);
            BlockBehaviour.Properties blockbehaviour$properties = owner.properties;
            this.lightEmission = blockbehaviour$properties.lightEmission.applyAsInt(this.asState());
            this.useShapeForLightOcclusion = owner.useShapeForLightOcclusion(this.asState());
            this.isAir = blockbehaviour$properties.isAir;
            this.ignitedByLava = blockbehaviour$properties.ignitedByLava;
            this.liquid = blockbehaviour$properties.liquid;
            this.pushReaction = blockbehaviour$properties.pushReaction;
            this.mapColor = blockbehaviour$properties.mapColor.apply(this.asState());
            this.destroySpeed = blockbehaviour$properties.destroyTime;
            this.requiresCorrectToolForDrops = blockbehaviour$properties.requiresCorrectToolForDrops;
            this.canOcclude = blockbehaviour$properties.canOcclude;
            this.isRedstoneConductor = blockbehaviour$properties.isRedstoneConductor;
            this.isSuffocating = blockbehaviour$properties.isSuffocating;
            this.isViewBlocking = blockbehaviour$properties.isViewBlocking;
            this.hasPostProcess = blockbehaviour$properties.hasPostProcess;
            this.emissiveRendering = blockbehaviour$properties.emissiveRendering;
            this.offsetFunction = blockbehaviour$properties.offsetFunction;
            this.spawnTerrainParticles = blockbehaviour$properties.spawnTerrainParticles;
            this.instrument = blockbehaviour$properties.instrument;
            this.replaceable = blockbehaviour$properties.replaceable;
        }

        private boolean calculateSolid() {
            if (this.owner.properties.forceSolidOn) {
                return true;
            } else if (this.owner.properties.forceSolidOff) {
                return false;
            } else if (this.cache == null) {
                return false;
            } else {
                VoxelShape voxelshape = this.cache.collisionShape;
                if (voxelshape.isEmpty()) {
                    return false;
                } else {
                    AABB aabb = voxelshape.bounds();
                    return aabb.getSize() >= 0.7291666666666666 ? true : aabb.getYsize() >= 1.0;
                }
            }
        }

        public void initCache() {
            this.fluidState = this.owner.getFluidState(this.asState());
            this.isRandomlyTicking = this.owner.isRandomlyTicking(this.asState());
            if (!this.getBlock().hasDynamicShape()) {
                this.cache = new BlockBehaviour.BlockStateBase.Cache(this.asState());
            }

            this.legacySolid = this.calculateSolid();
        }

        public Block getBlock() {
            return this.owner;
        }

        public Holder<Block> getBlockHolder() {
            return this.owner.builtInRegistryHolder();
        }

        @Deprecated
        public boolean blocksMotion() {
            Block block = this.getBlock();
            return block != Blocks.COBWEB && block != Blocks.BAMBOO_SAPLING && this.isSolid();
        }

        @Deprecated
        public boolean isSolid() {
            return this.legacySolid;
        }

        public boolean isValidSpawn(BlockGetter level, BlockPos pos, EntityType<?> entityType) {
            return this.getBlock().properties.isValidSpawn.test(this.asState(), level, pos, entityType);
        }

        public boolean propagatesSkylightDown(BlockGetter level, BlockPos pos) {
            return this.cache != null ? this.cache.propagatesSkylightDown : this.getBlock().propagatesSkylightDown(this.asState(), level, pos);
        }

        public int getLightBlock(BlockGetter level, BlockPos pos) {
            return this.cache != null ? this.cache.lightBlock : this.getBlock().getLightBlock(this.asState(), level, pos);
        }

        public VoxelShape getFaceOcclusionShape(BlockGetter level, BlockPos pos, Direction direction) {
            return this.cache != null && this.cache.occlusionShapes != null
                ? this.cache.occlusionShapes[direction.ordinal()]
                : Shapes.getFaceShape(this.getOcclusionShape(level, pos), direction);
        }

        public VoxelShape getOcclusionShape(BlockGetter level, BlockPos pos) {
            return this.getBlock().getOcclusionShape(this.asState(), level, pos);
        }

        public boolean hasLargeCollisionShape() {
            return this.cache == null || this.cache.largeCollisionShape;
        }

        public boolean useShapeForLightOcclusion() {
            return this.useShapeForLightOcclusion;
        }

        /** @deprecated Forge: Use {@link BlockState#getLightEmission(BlockGetter, BlockPos)} instead */
        @Deprecated
        public int getLightEmission() {
            return this.lightEmission;
        }

        public boolean isAir() {
            return this.getBlock().isAir((BlockState)this);
        }

        public boolean ignitedByLava() {
            return this.ignitedByLava;
        }

        @Deprecated
        public boolean liquid() {
            return this.liquid;
        }

        public MapColor getMapColor(BlockGetter level, BlockPos pos) {
            return getBlock().getMapColor(this.asState(), level, pos, this.mapColor);
        }

        /**
 * @return the blockstate with the given rotation. If inapplicable, returns itself.
 *
 * @deprecated use {@link BlockState#rotate(LevelAccessor, BlockPos, Rotation)}
 */
        @Deprecated
        public BlockState rotate(Rotation rotation) {
            return this.getBlock().rotate(this.asState(), rotation);
        }

        /**
         * @return the blockstate mirrored in the given way. If inapplicable, returns itself.
         */
        public BlockState mirror(Mirror mirror) {
            return this.getBlock().mirror(this.asState(), mirror);
        }

        public RenderShape getRenderShape() {
            return this.getBlock().getRenderShape(this.asState());
        }

        public boolean emissiveRendering(BlockGetter level, BlockPos pos) {
            return this.emissiveRendering.test(this.asState(), level, pos);
        }

        public float getShadeBrightness(BlockGetter level, BlockPos pos) {
            return this.getBlock().getShadeBrightness(this.asState(), level, pos);
        }

        public boolean isRedstoneConductor(BlockGetter level, BlockPos pos) {
            return this.isRedstoneConductor.test(this.asState(), level, pos);
        }

        public boolean isSignalSource() {
            return this.getBlock().isSignalSource(this.asState());
        }

        public int getSignal(BlockGetter level, BlockPos pos, Direction direction) {
            return this.getBlock().getSignal(this.asState(), level, pos, direction);
        }

        public boolean hasAnalogOutputSignal() {
            return this.getBlock().hasAnalogOutputSignal(this.asState());
        }

        public int getAnalogOutputSignal(Level level, BlockPos pos) {
            return this.getBlock().getAnalogOutputSignal(this.asState(), level, pos);
        }

        public float getDestroySpeed(BlockGetter level, BlockPos pos) {
            return this.destroySpeed;
        }

        public float getDestroyProgress(Player player, BlockGetter level, BlockPos pos) {
            return this.getBlock().getDestroyProgress(this.asState(), player, level, pos);
        }

        public int getDirectSignal(BlockGetter level, BlockPos pos, Direction direction) {
            return this.getBlock().getDirectSignal(this.asState(), level, pos, direction);
        }

        public PushReaction getPistonPushReaction() {
            PushReaction reaction = getBlock().getPistonPushReaction(asState());
            if (reaction != null) return reaction;
            return this.pushReaction;
        }

        public boolean isSolidRender(BlockGetter level, BlockPos pos) {
            if (this.cache != null) {
                return this.cache.solidRender;
            } else {
                BlockState blockstate = this.asState();
                return blockstate.canOcclude() ? Block.isShapeFullBlock(blockstate.getOcclusionShape(level, pos)) : false;
            }
        }

        public boolean canOcclude() {
            return this.canOcclude;
        }

        public boolean skipRendering(BlockState state, Direction face) {
            return this.getBlock().skipRendering(this.asState(), state, face);
        }

        public VoxelShape getShape(BlockGetter level, BlockPos pos) {
            return this.getShape(level, pos, CollisionContext.empty());
        }

        public VoxelShape getShape(BlockGetter level, BlockPos pos, CollisionContext context) {
            return this.getBlock().getShape(this.asState(), level, pos, context);
        }

        public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos) {
            return this.cache != null ? this.cache.collisionShape : this.getCollisionShape(level, pos, CollisionContext.empty());
        }

        public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos, CollisionContext context) {
            return this.getBlock().getCollisionShape(this.asState(), level, pos, context);
        }

        public VoxelShape getBlockSupportShape(BlockGetter level, BlockPos pos) {
            return this.getBlock().getBlockSupportShape(this.asState(), level, pos);
        }

        public VoxelShape getVisualShape(BlockGetter level, BlockPos pos, CollisionContext context) {
            return this.getBlock().getVisualShape(this.asState(), level, pos, context);
        }

        public VoxelShape getInteractionShape(BlockGetter level, BlockPos pos) {
            return this.getBlock().getInteractionShape(this.asState(), level, pos);
        }

        public final boolean entityCanStandOn(BlockGetter level, BlockPos pos, Entity entity) {
            return this.entityCanStandOnFace(level, pos, entity, Direction.UP);
        }

        /**
         * @return true if the collision box of this state covers the entire upper face of the blockspace
         */
        public final boolean entityCanStandOnFace(BlockGetter level, BlockPos pos, Entity entity, Direction face) {
            return Block.isFaceFull(this.getCollisionShape(level, pos, CollisionContext.of(entity)), face);
        }

        public Vec3 getOffset(BlockGetter level, BlockPos pos) {
            BlockBehaviour.OffsetFunction blockbehaviour$offsetfunction = this.offsetFunction;
            return blockbehaviour$offsetfunction != null ? blockbehaviour$offsetfunction.evaluate(this.asState(), level, pos) : Vec3.ZERO;
        }

        public boolean hasOffsetFunction() {
            return this.offsetFunction != null;
        }

        public boolean triggerEvent(Level level, BlockPos pos, int id, int param) {
            return this.getBlock().triggerEvent(this.asState(), level, pos, id, param);
        }

        public void handleNeighborChanged(Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
            this.getBlock().neighborChanged(this.asState(), level, pos, block, fromPos, isMoving);
        }

        public final void updateNeighbourShapes(LevelAccessor level, BlockPos pos, int flags) {
            this.updateNeighbourShapes(level, pos, flags, 512);
        }

        public final void updateNeighbourShapes(LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
            BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();

            for (Direction direction : BlockBehaviour.UPDATE_SHAPE_ORDER) {
                blockpos$mutableblockpos.setWithOffset(pos, direction);
                level.neighborShapeChanged(direction.getOpposite(), this.asState(), blockpos$mutableblockpos, pos, flags, recursionLeft);
            }
        }

        public final void updateIndirectNeighbourShapes(LevelAccessor level, BlockPos pos, int flags) {
            this.updateIndirectNeighbourShapes(level, pos, flags, 512);
        }

        public void updateIndirectNeighbourShapes(LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
            this.getBlock().updateIndirectNeighbourShapes(this.asState(), level, pos, flags, recursionLeft);
        }

        public void onPlace(Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
            this.getBlock().onPlace(this.asState(), level, pos, oldState, movedByPiston);
        }

        public void onRemove(Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
            this.getBlock().onRemove(this.asState(), level, pos, newState, movedByPiston);
        }

        public void onExplosionHit(Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> dropConsumer) {
            this.getBlock().onExplosionHit(this.asState(), level, pos, explosion, dropConsumer);
        }

        public void tick(ServerLevel level, BlockPos pos, RandomSource random) {
            this.getBlock().tick(this.asState(), level, pos, random);
        }

        public void randomTick(ServerLevel level, BlockPos pos, RandomSource random) {
            this.getBlock().randomTick(this.asState(), level, pos, random);
        }

        public void entityInside(Level level, BlockPos pos, Entity entity) {
            this.getBlock().entityInside(this.asState(), level, pos, entity);
        }

        public void spawnAfterBreak(ServerLevel level, BlockPos pos, ItemStack stack, boolean dropExperience) {
            this.getBlock().spawnAfterBreak(this.asState(), level, pos, stack, dropExperience);
        }

        public List<ItemStack> getDrops(LootParams.Builder lootParams) {
            return this.getBlock().getDrops(this.asState(), lootParams);
        }

        public ItemInteractionResult useItemOn(ItemStack stack, Level level, Player player, InteractionHand hand, BlockHitResult hitResult) {
            var useOnContext = new net.minecraft.world.item.context.UseOnContext(level, player, hand, player.getItemInHand(hand).copy(), hitResult);
            var e = net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent(useOnContext, net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent.UsePhase.BLOCK));
            if (e.isCanceled()) return e.getCancellationResult();
            return this.getBlock().useItemOn(stack, this.asState(), level, hitResult.getBlockPos(), player, hand, hitResult);
        }

        public InteractionResult useWithoutItem(Level level, Player player, BlockHitResult hitResult) {
            return this.getBlock().useWithoutItem(this.asState(), level, hitResult.getBlockPos(), player, hitResult);
        }

        public void attack(Level level, BlockPos pos, Player player) {
            this.getBlock().attack(this.asState(), level, pos, player);
        }

        public boolean isSuffocating(BlockGetter level, BlockPos pos) {
            return this.isSuffocating.test(this.asState(), level, pos);
        }

        public boolean isViewBlocking(BlockGetter level, BlockPos pos) {
            return this.isViewBlocking.test(this.asState(), level, pos);
        }

        public BlockState updateShape(Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
            return this.getBlock().updateShape(this.asState(), direction, neighborState, level, pos, neighborPos);
        }

        public boolean isPathfindable(PathComputationType p_60650_) {
            return this.getBlock().isPathfindable(this.asState(), p_60650_);
        }

        public boolean canBeReplaced(BlockPlaceContext useContext) {
            return this.getBlock().canBeReplaced(this.asState(), useContext);
        }

        public boolean canBeReplaced(Fluid fluid) {
            return this.getBlock().canBeReplaced(this.asState(), fluid);
        }

        public boolean canBeReplaced() {
            return this.replaceable;
        }

        public boolean canSurvive(LevelReader level, BlockPos pos) {
            return this.getBlock().canSurvive(this.asState(), level, pos);
        }

        public boolean hasPostProcess(BlockGetter level, BlockPos pos) {
            return this.hasPostProcess.test(this.asState(), level, pos);
        }

        @Nullable
        public MenuProvider getMenuProvider(Level level, BlockPos pos) {
            return this.getBlock().getMenuProvider(this.asState(), level, pos);
        }

        public boolean is(TagKey<Block> tag) {
            return this.getBlock().builtInRegistryHolder().is(tag);
        }

        public boolean is(TagKey<Block> tag, Predicate<BlockBehaviour.BlockStateBase> predicate) {
            return this.is(tag) && predicate.test(this);
        }

        public boolean is(HolderSet<Block> holder) {
            return holder.contains(this.getBlock().builtInRegistryHolder());
        }

        public boolean is(Holder<Block> block) {
            return this.is(block.value());
        }

        public Stream<TagKey<Block>> getTags() {
            return this.getBlock().builtInRegistryHolder().tags();
        }

        public boolean hasBlockEntity() {
            return this.getBlock() instanceof EntityBlock;
        }

        @Nullable
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockEntityType<T> blockEntityType) {
            return this.getBlock() instanceof EntityBlock ? ((EntityBlock)this.getBlock()).getTicker(level, this.asState(), blockEntityType) : null;
        }

        public boolean is(Block block) {
            return this.getBlock() == block;
        }

        public boolean is(ResourceKey<Block> block) {
            return this.getBlock().builtInRegistryHolder().is(block);
        }

        public FluidState getFluidState() {
            return this.fluidState;
        }

        public boolean isRandomlyTicking() {
            return this.isRandomlyTicking;
        }

        public long getSeed(BlockPos pos) {
            return this.getBlock().getSeed(this.asState(), pos);
        }

        @Deprecated //Forge: Use more sensitive version {@link IForgeBlockState#getSoundType(IWorldReader, BlockPos, Entity) }
        public SoundType getSoundType() {
            return this.getBlock().getSoundType(this.asState());
        }

        public void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
            this.getBlock().onProjectileHit(level, state, hit, projectile);
        }

        public boolean isFaceSturdy(BlockGetter level, BlockPos pos, Direction direction) {
            return this.isFaceSturdy(level, pos, direction, SupportType.FULL);
        }

        public boolean isFaceSturdy(BlockGetter level, BlockPos pos, Direction face, SupportType supportType) {
            return this.cache != null ? this.cache.isFaceSturdy(face, supportType) : supportType.isSupporting(this.asState(), level, pos, face);
        }

        public boolean isCollisionShapeFullBlock(BlockGetter level, BlockPos pos) {
            return this.cache != null ? this.cache.isCollisionShapeFullBlock : this.getBlock().isCollisionShapeFullBlock(this.asState(), level, pos);
        }

        protected abstract BlockState asState();

        public boolean requiresCorrectToolForDrops() {
            return this.requiresCorrectToolForDrops;
        }

        public boolean shouldSpawnTerrainParticles() {
            return this.spawnTerrainParticles;
        }

        public NoteBlockInstrument instrument() {
            return this.instrument;
        }

        static final class Cache {
            private static final Direction[] DIRECTIONS = Direction.values();
            private static final int SUPPORT_TYPE_COUNT = SupportType.values().length;
            protected final boolean solidRender;
            final boolean propagatesSkylightDown;
            final int lightBlock;
            @Nullable
            final VoxelShape[] occlusionShapes;
            protected final VoxelShape collisionShape;
            protected final boolean largeCollisionShape;
            private final boolean[] faceSturdy;
            protected final boolean isCollisionShapeFullBlock;

            Cache(BlockState state) {
                Block block = state.getBlock();
                this.solidRender = state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                this.propagatesSkylightDown = block.propagatesSkylightDown(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                this.lightBlock = block.getLightBlock(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (!state.canOcclude()) {
                    this.occlusionShapes = null;
                } else {
                    this.occlusionShapes = new VoxelShape[DIRECTIONS.length];
                    VoxelShape voxelshape = block.getOcclusionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

                    for (Direction direction : DIRECTIONS) {
                        this.occlusionShapes[direction.ordinal()] = Shapes.getFaceShape(voxelshape, direction);
                    }
                }

                this.collisionShape = block.getCollisionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
                if (!this.collisionShape.isEmpty() && state.hasOffsetFunction()) {
                    throw new IllegalStateException(
                        String.format(
                            Locale.ROOT,
                            "%s has a collision shape and an offset type, but is not marked as dynamicShape in its properties.",
                            BuiltInRegistries.BLOCK.getKey(block)
                        )
                    );
                } else {
                    this.largeCollisionShape = Arrays.stream(Direction.Axis.values())
                        .anyMatch(p_60860_ -> this.collisionShape.min(p_60860_) < 0.0 || this.collisionShape.max(p_60860_) > 1.0);
                    this.faceSturdy = new boolean[DIRECTIONS.length * SUPPORT_TYPE_COUNT];

                    for (Direction direction1 : DIRECTIONS) {
                        for (SupportType supporttype : SupportType.values()) {
                            this.faceSturdy[getFaceSupportIndex(direction1, supporttype)] = supporttype.isSupporting(
                                state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction1
                            );
                        }
                    }

                    this.isCollisionShapeFullBlock = Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                }
            }

            public boolean isFaceSturdy(Direction direction, SupportType supportType) {
                return this.faceSturdy[getFaceSupportIndex(direction, supportType)];
            }

            private static int getFaceSupportIndex(Direction direction, SupportType supportType) {
                return direction.ordinal() * SUPPORT_TYPE_COUNT + supportType.ordinal();
            }
        }
    }

    public interface OffsetFunction {
        Vec3 evaluate(BlockState state, BlockGetter level, BlockPos pos);
    }

    public static enum OffsetType {
        NONE,
        XZ,
        XYZ;
    }

    public static class Properties {
        public static final Codec<BlockBehaviour.Properties> CODEC = Codec.unit(() -> of());
        Function<BlockState, MapColor> mapColor = p_284884_ -> MapColor.NONE;
        boolean hasCollision = true;
        SoundType soundType = SoundType.STONE;
        ToIntFunction<BlockState> lightEmission = p_60929_ -> 0;
        float explosionResistance;
        float destroyTime;
        boolean requiresCorrectToolForDrops;
        boolean isRandomlyTicking;
        float friction = 0.6F;
        float speedFactor = 1.0F;
        float jumpFactor = 1.0F;
        /**
         * Sets loot table information
         */
        ResourceKey<LootTable> drops;
        boolean canOcclude = true;
        boolean isAir;
        boolean ignitedByLava;
        @Deprecated
        boolean liquid;
        @Deprecated
        boolean forceSolidOff;
        boolean forceSolidOn;
        PushReaction pushReaction = PushReaction.NORMAL;
        boolean spawnTerrainParticles = true;
        NoteBlockInstrument instrument = NoteBlockInstrument.HARP;
        private java.util.function.Supplier<ResourceKey<LootTable>> lootTableSupplier;
        boolean replaceable;
        BlockBehaviour.StateArgumentPredicate<EntityType<?>> isValidSpawn = (p_284893_, p_284894_, p_284895_, p_284896_) -> p_284893_.isFaceSturdy(
                    p_284894_, p_284895_, Direction.UP
                )
                && p_284893_.getLightEmission(p_284894_, p_284895_) < 14;
        BlockBehaviour.StatePredicate isRedstoneConductor = (p_284888_, p_284889_, p_284890_) -> p_284888_.isCollisionShapeFullBlock(p_284889_, p_284890_);
        BlockBehaviour.StatePredicate isSuffocating = (p_284885_, p_284886_, p_284887_) -> p_284885_.blocksMotion()
                && p_284885_.isCollisionShapeFullBlock(p_284886_, p_284887_);
        /**
         * If it blocks vision on the client side.
         */
        BlockBehaviour.StatePredicate isViewBlocking = this.isSuffocating;
        BlockBehaviour.StatePredicate hasPostProcess = (p_60963_, p_60964_, p_60965_) -> false;
        BlockBehaviour.StatePredicate emissiveRendering = (p_60931_, p_60932_, p_60933_) -> false;
        boolean dynamicShape;
        FeatureFlagSet requiredFeatures = FeatureFlags.VANILLA_SET;
        @Nullable
        BlockBehaviour.OffsetFunction offsetFunction;

        private Properties() {
        }

        public static BlockBehaviour.Properties of() {
            return new BlockBehaviour.Properties();
        }

        public static BlockBehaviour.Properties ofFullCopy(BlockBehaviour blockBehaviour) {
            BlockBehaviour.Properties blockbehaviour$properties = ofLegacyCopy(blockBehaviour);
            BlockBehaviour.Properties blockbehaviour$properties1 = blockBehaviour.properties;
            blockbehaviour$properties.jumpFactor = blockbehaviour$properties1.jumpFactor;
            blockbehaviour$properties.isRedstoneConductor = blockbehaviour$properties1.isRedstoneConductor;
            blockbehaviour$properties.isValidSpawn = blockbehaviour$properties1.isValidSpawn;
            blockbehaviour$properties.hasPostProcess = blockbehaviour$properties1.hasPostProcess;
            blockbehaviour$properties.isSuffocating = blockbehaviour$properties1.isSuffocating;
            blockbehaviour$properties.isViewBlocking = blockbehaviour$properties1.isViewBlocking;
            blockbehaviour$properties.drops = blockbehaviour$properties1.drops;
            return blockbehaviour$properties;
        }

        @Deprecated
        public static BlockBehaviour.Properties ofLegacyCopy(BlockBehaviour blockBehaviour) {
            BlockBehaviour.Properties blockbehaviour$properties = new BlockBehaviour.Properties();
            BlockBehaviour.Properties blockbehaviour$properties1 = blockBehaviour.properties;
            blockbehaviour$properties.destroyTime = blockbehaviour$properties1.destroyTime;
            blockbehaviour$properties.explosionResistance = blockbehaviour$properties1.explosionResistance;
            blockbehaviour$properties.hasCollision = blockbehaviour$properties1.hasCollision;
            blockbehaviour$properties.isRandomlyTicking = blockbehaviour$properties1.isRandomlyTicking;
            blockbehaviour$properties.lightEmission = blockbehaviour$properties1.lightEmission;
            blockbehaviour$properties.mapColor = blockbehaviour$properties1.mapColor;
            blockbehaviour$properties.soundType = blockbehaviour$properties1.soundType;
            blockbehaviour$properties.friction = blockbehaviour$properties1.friction;
            blockbehaviour$properties.speedFactor = blockbehaviour$properties1.speedFactor;
            blockbehaviour$properties.dynamicShape = blockbehaviour$properties1.dynamicShape;
            blockbehaviour$properties.canOcclude = blockbehaviour$properties1.canOcclude;
            blockbehaviour$properties.isAir = blockbehaviour$properties1.isAir;
            blockbehaviour$properties.ignitedByLava = blockbehaviour$properties1.ignitedByLava;
            blockbehaviour$properties.liquid = blockbehaviour$properties1.liquid;
            blockbehaviour$properties.forceSolidOff = blockbehaviour$properties1.forceSolidOff;
            blockbehaviour$properties.forceSolidOn = blockbehaviour$properties1.forceSolidOn;
            blockbehaviour$properties.pushReaction = blockbehaviour$properties1.pushReaction;
            blockbehaviour$properties.requiresCorrectToolForDrops = blockbehaviour$properties1.requiresCorrectToolForDrops;
            blockbehaviour$properties.offsetFunction = blockbehaviour$properties1.offsetFunction;
            blockbehaviour$properties.spawnTerrainParticles = blockbehaviour$properties1.spawnTerrainParticles;
            blockbehaviour$properties.requiredFeatures = blockbehaviour$properties1.requiredFeatures;
            blockbehaviour$properties.emissiveRendering = blockbehaviour$properties1.emissiveRendering;
            blockbehaviour$properties.instrument = blockbehaviour$properties1.instrument;
            blockbehaviour$properties.replaceable = blockbehaviour$properties1.replaceable;
            return blockbehaviour$properties;
        }

        public BlockBehaviour.Properties mapColor(DyeColor mapColor) {
            this.mapColor = p_284892_ -> mapColor.getMapColor();
            return this;
        }

        public BlockBehaviour.Properties mapColor(MapColor mapColor) {
            this.mapColor = p_222988_ -> mapColor;
            return this;
        }

        public BlockBehaviour.Properties mapColor(Function<BlockState, MapColor> mapColor) {
            this.mapColor = mapColor;
            return this;
        }

        public BlockBehaviour.Properties noCollission() {
            this.hasCollision = false;
            this.canOcclude = false;
            return this;
        }

        public BlockBehaviour.Properties noOcclusion() {
            this.canOcclude = false;
            return this;
        }

        public BlockBehaviour.Properties friction(float friction) {
            this.friction = friction;
            return this;
        }

        public BlockBehaviour.Properties speedFactor(float speedFactor) {
            this.speedFactor = speedFactor;
            return this;
        }

        public BlockBehaviour.Properties jumpFactor(float jumpFactor) {
            this.jumpFactor = jumpFactor;
            return this;
        }

        public BlockBehaviour.Properties sound(SoundType soundType) {
            this.soundType = soundType;
            return this;
        }

        public BlockBehaviour.Properties lightLevel(ToIntFunction<BlockState> lightEmission) {
            this.lightEmission = lightEmission;
            return this;
        }

        public BlockBehaviour.Properties strength(float destroyTime, float explosionResistance) {
            return this.destroyTime(destroyTime).explosionResistance(explosionResistance);
        }

        public BlockBehaviour.Properties instabreak() {
            return this.strength(0.0F);
        }

        public BlockBehaviour.Properties strength(float strength) {
            this.strength(strength, strength);
            return this;
        }

        public BlockBehaviour.Properties randomTicks() {
            this.isRandomlyTicking = true;
            return this;
        }

        public BlockBehaviour.Properties dynamicShape() {
            this.dynamicShape = true;
            return this;
        }

        public BlockBehaviour.Properties noLootTable() {
            this.drops = BuiltInLootTables.EMPTY;
            return this;
        }

        @Deprecated // FORGE: Use the variant that takes a Supplier below
        public BlockBehaviour.Properties dropsLike(Block block) {
            this.lootTableSupplier = () -> block.getLootTable();
            return this;
        }

        public BlockBehaviour.Properties lootFrom(java.util.function.Supplier<? extends Block> blockIn) {
             this.lootTableSupplier = () -> blockIn.get().getLootTable();
             return this;
        }

        public BlockBehaviour.Properties ignitedByLava() {
            this.ignitedByLava = true;
            return this;
        }

        public BlockBehaviour.Properties liquid() {
            this.liquid = true;
            return this;
        }

        public BlockBehaviour.Properties forceSolidOn() {
            this.forceSolidOn = true;
            return this;
        }

        @Deprecated
        public BlockBehaviour.Properties forceSolidOff() {
            this.forceSolidOff = true;
            return this;
        }

        public BlockBehaviour.Properties pushReaction(PushReaction pushReaction) {
            this.pushReaction = pushReaction;
            return this;
        }

        public BlockBehaviour.Properties air() {
            this.isAir = true;
            return this;
        }

        public BlockBehaviour.Properties isValidSpawn(BlockBehaviour.StateArgumentPredicate<EntityType<?>> isValidSpawn) {
            this.isValidSpawn = isValidSpawn;
            return this;
        }

        public BlockBehaviour.Properties isRedstoneConductor(BlockBehaviour.StatePredicate isRedstoneConductor) {
            this.isRedstoneConductor = isRedstoneConductor;
            return this;
        }

        public BlockBehaviour.Properties isSuffocating(BlockBehaviour.StatePredicate isSuffocating) {
            this.isSuffocating = isSuffocating;
            return this;
        }

        /**
         * If it blocks vision on the client side.
         */
        public BlockBehaviour.Properties isViewBlocking(BlockBehaviour.StatePredicate isViewBlocking) {
            this.isViewBlocking = isViewBlocking;
            return this;
        }

        public BlockBehaviour.Properties hasPostProcess(BlockBehaviour.StatePredicate hasPostProcess) {
            this.hasPostProcess = hasPostProcess;
            return this;
        }

        public BlockBehaviour.Properties emissiveRendering(BlockBehaviour.StatePredicate emissiveRendering) {
            this.emissiveRendering = emissiveRendering;
            return this;
        }

        public BlockBehaviour.Properties requiresCorrectToolForDrops() {
            this.requiresCorrectToolForDrops = true;
            return this;
        }

        public BlockBehaviour.Properties destroyTime(float destroyTime) {
            this.destroyTime = destroyTime;
            return this;
        }

        public BlockBehaviour.Properties explosionResistance(float explosionResistance) {
            this.explosionResistance = Math.max(0.0F, explosionResistance);
            return this;
        }

        public BlockBehaviour.Properties offsetType(BlockBehaviour.OffsetType offsetType) {
            this.offsetFunction = switch (offsetType) {
                case NONE -> null;
                case XZ -> (p_272565_, p_272566_, p_272567_) -> {
                Block block = p_272565_.getBlock();
                long i = Mth.getSeed(p_272567_.getX(), 0, p_272567_.getZ());
                float f = block.getMaxHorizontalOffset();
                double d0 = Mth.clamp(((double)((float)(i & 15L) / 15.0F) - 0.5) * 0.5, (double)(-f), (double)f);
                double d1 = Mth.clamp(((double)((float)(i >> 8 & 15L) / 15.0F) - 0.5) * 0.5, (double)(-f), (double)f);
                return new Vec3(d0, 0.0, d1);
            };
                case XYZ -> (p_272562_, p_272563_, p_272564_) -> {
                Block block = p_272562_.getBlock();
                long i = Mth.getSeed(p_272564_.getX(), 0, p_272564_.getZ());
                double d0 = ((double)((float)(i >> 4 & 15L) / 15.0F) - 1.0) * (double)block.getMaxVerticalOffset();
                float f = block.getMaxHorizontalOffset();
                double d1 = Mth.clamp(((double)((float)(i & 15L) / 15.0F) - 0.5) * 0.5, (double)(-f), (double)f);
                double d2 = Mth.clamp(((double)((float)(i >> 8 & 15L) / 15.0F) - 0.5) * 0.5, (double)(-f), (double)f);
                return new Vec3(d1, d0, d2);
            };
            };
            return this;
        }

        public BlockBehaviour.Properties noTerrainParticles() {
            this.spawnTerrainParticles = false;
            return this;
        }

        public BlockBehaviour.Properties requiredFeatures(FeatureFlag... requiredFeatures) {
            this.requiredFeatures = FeatureFlags.REGISTRY.subset(requiredFeatures);
            return this;
        }

        public BlockBehaviour.Properties instrument(NoteBlockInstrument instrument) {
            this.instrument = instrument;
            return this;
        }

        public BlockBehaviour.Properties replaceable() {
            this.replaceable = true;
            return this;
        }
    }

    public interface StateArgumentPredicate<A> {
        boolean test(BlockState state, BlockGetter level, BlockPos pos, A value);
    }

    public interface StatePredicate {
        boolean test(BlockState state, BlockGetter level, BlockPos pos);
    }
}
