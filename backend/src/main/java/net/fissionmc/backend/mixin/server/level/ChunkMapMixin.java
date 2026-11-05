package net.fissionmc.backend.mixin.server.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.fissionmc.backend.world.ChunkCallbackExecutor;
import net.fissionmc.backend.world.ChunkMapBridge;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/server/level/ChunkMap.java.patch */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements ChunkMapBridge {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private ServerLevel level;
    @Shadow @Final @Mutable private RandomState randomState;
    @Shadow @Final private PlayerMap playerMap;
    @Shadow @Final private Int2ObjectMap<?> entityMap;
    @Shadow private static double euclideanDistanceSquared(final ChunkPos chunkPos, final Vec3 pos) { throw new AssertionError(); }

    // CraftBukkit start - recursion-safe executor for Chunk loadCallback() and unloadCallback()
    private final ChunkCallbackExecutor fission$callbackExecutor = new ChunkCallbackExecutor();

    @Override
    public ChunkCallbackExecutor fission$callbackExecutor() {
        return this.fission$callbackExecutor;
    }
    // CraftBukkit end

    // Paper start
    public final ChunkHolder getUnloadingChunkHolder(int chunkX, int chunkZ) {
        return this.pendingUnloads.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }
    // Paper end

    // Paper start - Optional per player mob spawns
    public int getMobCountNear(final ServerPlayer player, final net.minecraft.world.entity.MobCategory mobCategory) {
        return -1; // TODO(behaviour): features/0027 + 0028 (per-player mob counts)
    }
    // Paper end

    // CraftBukkit start - SPIGOT-7051: It's a rigged game! Use delegate for random state creation, otherwise it is not so random.
    // FissionMC: vanilla already created the RandomState from the (non-noise) CustomChunkGenerator; recreate it from the
    // delegate before the structure state is built from it.
    // TODO(behaviour): Spigot passes level.spigotConfig to createState (per-world structure seeds, ChunkGeneratorStructureState patch)
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createState(Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"))
    private ChunkGeneratorStructureState paper$delegateRandomState(final ChunkGenerator generator, final HolderLookup<StructureSet> structureSets, final RandomState randomState, final long levelSeed, final Operation<ChunkGeneratorStructureState> original, @Local(argsOnly = true) final ServerLevel level) {
        RandomState state = randomState;
        if (generator instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator customChunkGenerator
            && customChunkGenerator.getDelegate() instanceof NoiseBasedChunkGenerator noiseGenerator) {
            state = RandomState.create(level.registryAccess().lookupOrThrow(Registries.NOISE), levelSeed, noiseGenerator.generatorSettings().value());
            this.randomState = state;
        }
        return original.call(generator, structureSets, state, levelSeed);
    }
    // CraftBukkit end

    // Spigot start - collectSpawningChunks uses the reduced (PlayerNaturallySpawnCreaturesEvent) range
    @Redirect(method = "collectSpawningChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;anyPlayerCloseEnoughForSpawningInternal(Lnet/minecraft/world/level/ChunkPos;)Z"))
    private boolean paper$reducedSpawnRange(final ChunkMap chunkMap, final ChunkPos pos) {
        return this.anyPlayerCloseEnoughForSpawningInternal(pos, true);
    }

    private boolean anyPlayerCloseEnoughForSpawningInternal(final ChunkPos pos, final boolean reducedRange) {
        double blockRange; // Paper - use from event
        // Spigot end
        for (ServerPlayer player : this.playerMap.getAllPlayers()) {
            // Paper start - PlayerNaturallySpawnCreaturesEvent
            com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event;
            blockRange = 16384.0;
            if (reducedRange) {
                event = player.playerNaturallySpawnedEvent;
                if (event == null || event.isCancelled()) continue;
                blockRange = (double) ((event.getSpawnRadius() << 4) * (event.getSpawnRadius() << 4));
            }
            if (this.playerIsCloseEnoughForSpawning(player, pos, blockRange)) {
                // Paper end - PlayerNaturallySpawnCreaturesEvent
                return true;
            }
        }

        return false;
    }

    private boolean playerIsCloseEnoughForSpawning(final ServerPlayer player, final ChunkPos pos, final double range) { // Spigot
        if (player.isSpectator()) {
            return false;
        }

        double distanceToChunk = euclideanDistanceSquared(pos, player.position());
        return distanceToChunk < range; // Spigot
    }

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void paper$checkAddEntity(final Entity entity, final CallbackInfo ci) {
        org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
        // Paper start - ignore and warn about illegal addEntity calls instead of crashing server
        if (!entity.valid || entity.level() != this.level || this.entityMap.containsKey(entity.getId())) {
            LOGGER.error("Illegal ChunkMap::addEntity for world " + io.papermc.paper.util.MCUtil.getLevelName(this.level)
                + ": " + entity  + (this.entityMap.containsKey(entity.getId()) ? " ALREADY CONTAINED (This would have crashed your server)" : ""), new Throwable());
            ci.cancel();
            return;
        }
        // Paper end - ignore and warn about illegal addEntity calls instead of crashing server
        if (entity instanceof ServerPlayer && ((ServerPlayer) entity).suppressTrackerForLogin) ci.cancel(); // Paper - Fire PlayerJoinEvent when Player is actually ready; Delay adding to tracker until after list packets
    }

    @ModifyVariable(method = "addEntity", at = @At(value = "STORE"), ordinal = 0)
    private int paper$trackingRange(final int range, @Local(argsOnly = true) final Entity entity) {
        return org.spigotmc.TrackingRange.getEntityTrackingRange(entity, range); // Spigot
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void paper$asyncRemoveEntity(final Entity entity, final CallbackInfo ci) {
        org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
    }
    // TODO(behaviour): getChunkDataFixContextTag level stem (Paper - pass level stem; datafixers for non-minecraft dimensions)
    // TODO(moonrise): PlatformHooks onChunkHolderCreate/onChunkHolderDelete, getVisibleChunkHolders (debug/save iteration)
}
