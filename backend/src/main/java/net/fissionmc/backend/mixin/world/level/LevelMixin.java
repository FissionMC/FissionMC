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
    // TODO(behaviour): getChunk(int, int)/getBlockState/setBlock/setBlockEntity/getBlockEntity/destroyBlock capture + fast-path hunks
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
}
