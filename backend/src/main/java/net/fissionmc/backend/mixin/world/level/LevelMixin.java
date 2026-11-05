package net.fissionmc.backend.mixin.world.level;

import java.util.List;
import java.util.Map;
import net.fissionmc.backend.world.ServerLevelInit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.SpawnCategory;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/Level.java.patch */
@Mixin(Level.class)
public abstract class LevelMixin {
    // FissionMC: abstract in Paper (ServerLevelMixin implements it); a concrete declaration in Level is needed so
    // invokevirtual Level.getTypeKey resolves at runtime.
    public ResourceKey<net.minecraft.world.level.dimension.LevelStem> getTypeKey() {
        throw new AbstractMethodError("FissionMC: " + this.getClass().getName() + " does not implement getTypeKey");
    }

    @Shadow @Final private ResourceKey<Level> dimension;
    @Shadow protected abstract LevelEntityGetter<Entity> getEntities();

    // CraftBukkit start
    private CraftWorld world;
    private org.bukkit.generator.@Nullable ChunkGenerator generator;

    private boolean captureBlockStates = false;
    private boolean captureTreeGeneration = false;
    private Map<BlockPos, CraftBlockState> capturedBlockStates = new java.util.LinkedHashMap<>(); // Paper
    private Map<BlockPos, BlockEntity> capturedBlockEntities = new java.util.LinkedHashMap<>(); // Paper - Retain block place order when capturing blockstates
    private @Nullable List<net.minecraft.world.entity.item.ItemEntity> captureDrops;
    private final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<SpawnCategory> ticksPerSpawnCategory = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
    private boolean populating;
    private org.spigotmc.SpigotWorldConfig spigotConfig; // Spigot
    // Paper start - add paper world config
    private io.papermc.paper.configuration.WorldConfiguration paperConfig;
    public io.papermc.paper.configuration.WorldConfiguration paperConfig() {
        return this.paperConfig;
    }
    // Paper end - add paper world config

    private static @Nullable BlockPos lastPhysicsProblem; // Spigot
    // TODO(behaviour): explosionDensityCache (needs ServerExplosion.CacheKey) and redstoneUpdateInfos (RedstoneTorchBlock.Toggle)

    public CraftWorld getWorld() {
        return this.world;
    }

    public CraftServer getCraftServer() {
        return (CraftServer) org.bukkit.Bukkit.getServer();
    }
    // TODO(behaviour): hasChunk override (getChunkIfLoaded)
    // Paper start - per world ticks per spawn
    private int getTicksPerSpawn(SpawnCategory spawnCategory) {
        final int perWorld = this.paperConfig().entities.spawning.ticksPerSpawn.getInt(org.bukkit.craftbukkit.util.CraftSpawnCategory.toNMS(spawnCategory));
        if (perWorld >= 0) {
            return perWorld;
        }
        return this.getCraftServer().getTicksPerSpawns(spawnCategory);
    }
    // Paper end

    // FissionMC: the part of Paper's Level constructor that consumes the added parameters (see ServerLevel.create)
    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        final ServerLevelInit init = ServerLevelInit.peek();
        if (init == null) {
            return; // TODO(behaviour): ServerLevels constructed without ServerLevel.create (vanilla world loading) get no Bukkit state
        }
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        final ResourceKey<Level> dimension = this.dimension;
        final String bukkitName = init.loadedWorldData().bukkitName();
        final org.bukkit.NamespacedKey worldKey = CraftNamespacedKey.fromMinecraft(dimension.identifier()); // Paper
        this.spigotConfig = new org.spigotmc.SpigotWorldConfig(bukkitName, worldKey); // Spigot
        this.paperConfig = server.paperConfigurations.createWorldConfig(io.papermc.paper.configuration.PaperConfigurations.createWorldContextMap(server.storageSource.getDimensionPath(dimension), dimension.identifier(), this.spigotConfig, server.registryAccess(), init.gameRules())); // Paper - create paper world config
        this.generator = init.gen();
        this.world = new CraftWorld((ServerLevel) (Object) this, worldKey, init.biomeProvider(), init.env());

        for (SpawnCategory spawnCategory : SpawnCategory.values()) {
            if (org.bukkit.craftbukkit.util.CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                this.ticksPerSpawnCategory.put(spawnCategory, this.getTicksPerSpawn(spawnCategory));
            }
        }
        // CraftBukkit end
    }

    // Paper start - Cancel hit for vanished players
    // ret true if no collision
    public final boolean checkEntityCollision(BlockState state, Entity source, net.minecraft.world.phys.shapes.CollisionContext context,
                                              BlockPos pos, boolean checkCanSee) {
        final Level self = (Level) (Object) this;
        // Copied from CollisionGetter#isUnobstructed(BlockState, BlockPos, CollisionContext) & EntityGetter#isUnobstructed(Entity, VoxelShape)
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(self, pos, context);
        if (shape.isEmpty()) {
            return true;
        }

        shape = shape.move((double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
        if (shape.isEmpty()) {
            return true;
        }

        List<Entity> entities = self.getEntities(null, shape.bounds());
        for (int i = 0, len = entities.size(); i < len; ++i) {
            Entity entity = entities.get(i);

            if (checkCanSee && source instanceof net.minecraft.server.level.ServerPlayer && entity instanceof net.minecraft.server.level.ServerPlayer
                && !((net.minecraft.server.level.ServerPlayer) source).getBukkitEntity().canSee(((net.minecraft.server.level.ServerPlayer) entity).getBukkitEntity())) {
                continue;
            }

            // !entity.isRemoved() && entity.blocksBuilding && (source == null || !entity.isPassengerOfSameVehicle(source))
            // elide the last check since vanilla calls with source = null
            // only we care about the source for the canSee check
            if (entity.isRemoved() || !entity.blocksBuilding) {
                continue;
            }

            if (net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape, net.minecraft.world.phys.shapes.Shapes.create(entity.getBoundingBox()), net.minecraft.world.phys.shapes.BooleanOp.AND)) {
                return false;
            }
        }

        return true;
    }
    // Paper end - Cancel hit for vanished players

    // Paper start
    public HitResult.Type clipDirect(Vec3 start, Vec3 end, net.minecraft.world.phys.shapes.CollisionContext context) {
        // To be patched over
        return ((Level) (Object) this).clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context)).getType();
    }
    // Paper end

    // Paper start - if loaded
    // TODO(behaviour): getChunk(int, int)/getBlockState fast paths (perf only)
    public final @Nullable ChunkAccess getChunkIfLoadedImmediately(int x, int z) {
        return ((ServerLevel) (Object) this).getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    public final @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
        // CraftBukkit start - tree generation
        if (this.captureTreeGeneration) {
            CraftBlockState previous = this.capturedBlockStates.get(pos);
            if (previous != null) {
                return previous.getHandle();
            }
        }
        // CraftBukkit end
        if (((Level) (Object) this).isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        } else {
            ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);

            return chunk == null ? null : chunk.getBlockState(pos);
        }
    }

    public final @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
        ChunkAccess chunk = this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);

        return chunk == null ? null : chunk.getFluidState(pos);
    }

    public final boolean isLoadedAndInBounds(BlockPos pos) { // Paper - final for inline
        return ((Level) (Object) this).getWorldBorder().isWithinBounds(pos) && this.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    public @Nullable LevelChunk getChunkIfLoaded(int x, int z) { // Overridden in ServerLevel for ABI compat which has final
        return ((ServerLevel) (Object) this).getChunkSource().getChunkAtIfLoadedImmediately(x, z);
    }

    public final @Nullable LevelChunk getChunkIfLoaded(BlockPos pos) {
        return ((ServerLevel) (Object) this).getChunkSource().getChunkAtIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public final @Nullable BlockState getBlockStateIfLoadedAndInBounds(BlockPos pos) {
        return ((Level) (Object) this).getWorldBorder().isWithinBounds(pos) ? this.getBlockStateIfLoaded(pos) : null;
    }
    // Paper end

    // CraftBukkit start - Split off from above in order to directly send client and physic updates
    public void notifyAndUpdatePhysics(BlockPos pos, @Nullable LevelChunk chunk, BlockState oldState, BlockState blockState, BlockState newState, @Block.UpdateFlags int updateFlags, int updateLimit) {
        final Level self = (Level) (Object) this;
        if (newState == blockState) {
            if (oldState != newState) {
                self.setBlocksDirty(pos, oldState, newState);
            }

            if ((updateFlags & Block.UPDATE_CLIENTS) != 0 && (!self.isClientSide() || (updateFlags & Block.UPDATE_INVISIBLE) == 0) && (self.isClientSide() || chunk == null || (chunk.getFullStatus() != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)))) { // allow chunk to be null here as chunk.isReady() is false when we send our notification during block placement
                self.sendBlockUpdated(pos, oldState, blockState, updateFlags);
            }

            if ((updateFlags & Block.UPDATE_NEIGHBORS) != 0) {
                self.updateNeighborsAt(pos, oldState.getBlock());
                if (!self.isClientSide() && blockState.hasAnalogOutputSignal()) {
                    self.updateNeighbourForOutputSignal(pos, blockState.getBlock());
                }
            }

            if ((updateFlags & Block.UPDATE_KNOWN_SHAPE) == 0 && updateLimit > 0) {
                int neighbourUpdateFlags = updateFlags & ~(Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_NEIGHBORS);

                // CraftBukkit start
                oldState.updateIndirectNeighbourShapes(self, pos, neighbourUpdateFlags, updateLimit - 1); // Don't call an event for the old block to limit event spam
                boolean cancelledUpdates = false; // Paper - Fix block place logic
                if (((ServerLevel) self).hasPhysicsEvent) { // Paper - BlockPhysicsEvent
                    org.bukkit.event.block.BlockPhysicsEvent event = new org.bukkit.event.block.BlockPhysicsEvent(org.bukkit.craftbukkit.block.CraftBlock.at(self, pos), blockState.asBlockData());
                    cancelledUpdates = !event.callEvent(); // Paper - Fix block place logic
                }
                // CraftBukkit end
                if (!cancelledUpdates) { // Paper - Fix block place logic
                    blockState.updateNeighbourShapes(self, pos, neighbourUpdateFlags, updateLimit - 1);
                    blockState.updateIndirectNeighbourShapes(self, pos, neighbourUpdateFlags, updateLimit - 1);
                } // Paper - Fix block place logic
            }

            if ((updateFlags & Block.UPDATE_SKIP_POI) == 0) // Paper - temporary flag
            self.updatePOIOnBlockStateChange(pos, oldState, newState);
        }
    }
    // CraftBukkit end

    // Paper start - allow patching this logic
    public final int getEntityCount() {
        int ret = 0;
        for (net.minecraft.world.entity.Entity entity : this.getEntities().getAll()) {
            if (entity.getBukkitEntity().isValid()) {
                ++ret;
            }
        }
        return ret;
    }
    // Paper end - allow patching this logic

    // ---- wave 2: behaviour hunks (Paper: sources/net/minecraft/world/level/Level.java.patch) ----
    @Shadow public abstract boolean isInValidBounds(BlockPos pos);
    @Shadow public abstract LevelChunk getChunkAt(BlockPos pos);

    // FissionMC: replaced body (Paper rewrites most of setBlock: tree capture, block-state capture, notifyAndUpdatePhysics split)
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void paper$setBlock(final BlockPos pos, final BlockState blockState, final @Block.UpdateFlags int updateFlags, final int updateLimit, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        final Level self = (Level) (Object) this;
        // CraftBukkit start - tree generation
        if (this.captureTreeGeneration) {
            // Paper start - Protect Bedrock and End Portal/Frames from being destroyed
            BlockState type = self.getBlockState(pos);
            if (!net.fissionmc.backend.server.PaperDestroyable.isDestroyable(type)) {
                cir.setReturnValue(false);
                return;
            }
            // Paper end - Protect Bedrock and End Portal/Frames from being destroyed
            CraftBlockState blockstate = this.capturedBlockStates.get(pos);
            if (blockstate == null) {
                blockstate = org.bukkit.craftbukkit.block.CapturedBlockState.getTreeBlockState(self, pos, updateFlags);
                this.capturedBlockStates.put(pos.immutable(), blockstate);
            }
            blockstate.setBlock(blockState);
            blockstate.setFlags(updateFlags);
            cir.setReturnValue(true);
            return;
        }
        // CraftBukkit end
        if (!this.isInValidBounds(pos)) {
            cir.setReturnValue(false);
            return;
        }

        if (!self.isClientSide() && self.isDebug()) {
            cir.setReturnValue(false);
            return;
        }

        LevelChunk chunk = this.getChunkAt(pos);
        Block block = blockState.getBlock();
        // CraftBukkit start - capture blockstates
        boolean captured = false;
        if (this.captureBlockStates) {
            final CraftBlockState snapshot;
            if (!this.capturedBlockStates.containsKey(pos)) {
                snapshot = (CraftBlockState) org.bukkit.craftbukkit.block.CraftBlock.at(self, pos).getState(); // Paper - use CB getState to get a suitable snapshot
                this.capturedBlockStates.put(pos.immutable(), snapshot);
                captured = true;
            } else {
                snapshot = this.capturedBlockStates.get(pos);
            }
            snapshot.setFlags(updateFlags); // Paper - always set the flag of the most recent call to mitigate issues with multiple update at the same pos with different flags
        }
        // CraftBukkit end - capture blockstates
        BlockState oldState = chunk.setBlockState(pos, blockState, updateFlags);
        if (oldState == null) {
            // CraftBukkit start - remove blockstate if failed (or the same)
            if (this.captureBlockStates && captured) {
                this.capturedBlockStates.remove(pos);
            }
            // CraftBukkit end
            cir.setReturnValue(false);
            return;
        }

        BlockState newState = self.getBlockState(pos);

        // CraftBukkit start
        if (!this.captureBlockStates) { // Don't notify clients or update physics while capturing blockstates
            // Modularize client and physic updates
            // Spigot start
            try {
                this.notifyAndUpdatePhysics(pos, chunk, oldState, blockState, newState, updateFlags, updateLimit);
            } catch (StackOverflowError ex) {
                lastPhysicsProblem = pos.immutable();
            }
            // Spigot end
        }
        // CraftBukkit end

        cir.setReturnValue(true);
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void paper$treeCaptureGetBlockState(final BlockPos pos, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<BlockState> cir) {
        // CraftBukkit start - tree generation
        if (this.captureTreeGeneration) {
            CraftBlockState previous = this.capturedBlockStates.get(pos); // Paper
            if (previous != null) {
                cir.setReturnValue(previous.getHandle());
            }
        }
        // CraftBukkit end
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void paper$capturedGetBlockEntity(final BlockPos pos, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<BlockEntity> cir) {
        // Paper start - Perf: Optimize capturedTileEntities lookup
        BlockEntity blockEntity;
        if (!this.capturedBlockEntities.isEmpty() && (blockEntity = this.capturedBlockEntities.get(pos)) != null) {
            cir.setReturnValue(blockEntity);
        }
        // Paper end - Perf: Optimize capturedTileEntities lookup
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void paper$capturedSetBlockEntity(final BlockEntity blockEntity, final CallbackInfo ci) {
        BlockPos pos = blockEntity.getBlockPos();
        // CraftBukkit start
        if (this.isInValidBounds(pos) && this.captureBlockStates) {
            this.capturedBlockEntities.put(pos.immutable(), blockEntity);
            ci.cancel();
        }
        // CraftBukkit end
    }

    // FissionMC: replaced body (Paper - BlockDestroyEvent; Paper - Properly handle xp dropping)
    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
    private void paper$destroyBlock(final BlockPos pos, boolean dropResources, final @Nullable Entity breaker, final int updateLimit, final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        final Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState blockState = self.getBlockState(pos);
        if (blockState.isAir()) {
            cir.setReturnValue(false);
            return;
        }
        // Paper start - BlockDestroyEvent; while the above removeBlock method looks very similar
        // they are NOT used with same intent and the above should not fire this event. The above method is more of a BlockSetToAirEvent,
        // it doesn't imply destruction of a block that plays a sound effect / drops an item.
        FluidState fluidState = self.getFluidState(pos);
        boolean playEffect = true;
        BlockState effectType = blockState;
        int xp = blockState.getBlock().getExpDrop(blockState, serverLevel, pos, net.minecraft.world.item.ItemStack.EMPTY, true);
        if (com.destroystokyo.paper.event.block.BlockDestroyEvent.getHandlerList().getRegisteredListeners().length > 0) {
            com.destroystokyo.paper.event.block.BlockDestroyEvent event = new com.destroystokyo.paper.event.block.BlockDestroyEvent(org.bukkit.craftbukkit.block.CraftBlock.at(self, pos), fluidState.createLegacyBlock().asBlockData(), effectType.asBlockData(), xp, dropResources);
            if (!event.callEvent()) {
                cir.setReturnValue(false);
                return;
            }
            effectType = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getEffectBlock()).getState();
            playEffect = event.playEffect();
            dropResources = event.willDrop();
            xp = event.getExpToDrop();
        }
        // Paper end - BlockDestroyEvent
        if (playEffect) { // Paper - BlockDestroyEvent
            effectType.getBlock().spawnDestroyParticles(self, pos, effectType); // Paper - BlockDestroyEvent
        }

        if (dropResources) {
            BlockEntity blockEntity = blockState.hasBlockEntity() ? self.getBlockEntity(pos) : null;
            Block.dropResources(blockState, self, pos, blockEntity, breaker, net.minecraft.world.item.ItemStack.EMPTY, false); // Paper - Properly handle xp dropping
            // Paper - Properly handle xp dropping; custom amount (FissionMC: body of Paper's Block.popExperience(level, pos, xp, breaker))
            if (serverLevel.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.BLOCK_DROPS)) {
                net.minecraft.world.entity.ExperienceOrb.awardWithDirection(serverLevel, Vec3.atCenterOf(pos), Vec3.ZERO, xp, org.bukkit.entity.ExperienceOrb.SpawnReason.BLOCK_BREAK, breaker, null); // Paper
            }
        }

        boolean destroyed = self.setBlock(pos, fluidState.createLegacyBlock(), Block.UPDATE_ALL, updateLimit);
        if (destroyed) {
            self.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_DESTROY, pos, net.minecraft.world.level.gameevent.GameEvent.Context.of(breaker, blockState));
        }

        cir.setReturnValue(destroyed);
    }

    // Paper start - Prevent block entity and entity crashes
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "guardEntityTick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void paper$guardEntityTick(final java.util.function.Consumer<Object> tick, final Object object, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try {
            original.call(tick, object);
        } catch (Throwable t) {
            final Entity entity = (Entity) object;
            final String msg = String.format("Entity threw exception at %s:%s,%s,%s", io.papermc.paper.util.MCUtil.getLevelName(entity.level()), entity.getX(), entity.getY(), entity.getZ());
            net.minecraft.server.MinecraftServer.LOGGER.error(msg, t);
            this.getCraftServer().getPluginManager().callEvent(new com.destroystokyo.paper.event.server.ServerExceptionEvent(new com.destroystokyo.paper.exception.ServerInternalException(msg, t))); // Paper - ServerExceptionEvent
            entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);
        }
    }
    // Paper end - Prevent block entity and entity crashes
}
