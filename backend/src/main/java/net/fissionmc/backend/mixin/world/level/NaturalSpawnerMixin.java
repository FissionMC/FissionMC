package net.fissionmc.backend.mixin.world.level;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/level/NaturalSpawner.java.patch */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Shadow @Final private static int MAGIC_NUMBER;
    @Shadow @Final private static MobCategory[] SPAWNING_CATEGORIES;

    // Paper start - Add mobcaps commands
    private static int globalLimitForCategory(final ServerLevel level, final MobCategory category, final int spawnableChunkCount) {
        final int categoryLimit = level.getWorld().getSpawnLimitUnsafe(org.bukkit.craftbukkit.util.CraftSpawnCategory.toBukkit(category));
        if (categoryLimit < 1) {
            return categoryLimit;
        }
        return categoryLimit * spawnableChunkCount / MAGIC_NUMBER;
    }
    // Paper end - Add mobcaps commands

    // Paper start - Only count natural spawns
    @WrapWithCondition(method = "createState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;query(JLjava/util/function/Consumer;)V"))
    private static boolean paper$countNaturalOnly(final NaturalSpawner.ChunkGetter chunkGetter, final long chunkKey, final java.util.function.Consumer<?> consumer, @Local(ordinal = 0) final Entity entity) {
        return entity.level().paperConfig().entities.spawning.countAllMobsForSpawning
            || entity.spawnReason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL
            || entity.spawnReason == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }
    // Paper end - Only count natural spawns

    // FissionMC: Paper changes the signature of the 3-argument method; vanilla's stays (ServerChunkCacheMixin calls this one)
    private static List<MobCategory> getFilteredSpawningCategories(
        final NaturalSpawner.SpawnState state, final boolean spawnFriendlies, final boolean spawnEnemies, final boolean spawnPersistent, final ServerLevel level // CraftBukkit - add level/spawnFriendlies
    ) {
        net.minecraft.world.level.storage.LevelData worlddata = level.getLevelData(); // CraftBukkit - Other mob type spawn tick rate
        List<MobCategory> spawningCategories = new ArrayList<>(SPAWNING_CATEGORIES.length);

        for (MobCategory mobCategory : SPAWNING_CATEGORIES) {
            // CraftBukkit start - Use per-world spawn limits
            boolean spawnThisTick = true;
            int limit = mobCategory.getMaxInstancesPerChunk();
            org.bukkit.entity.SpawnCategory spawnCategory = org.bukkit.craftbukkit.util.CraftSpawnCategory.toBukkit(mobCategory);
            if (org.bukkit.craftbukkit.util.CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                spawnThisTick = level.ticksPerSpawnCategory.getLong(spawnCategory) != 0 && worlddata.getGameTime() % level.ticksPerSpawnCategory.getLong(spawnCategory) == 0;
                limit = level.getWorld().getSpawnLimit(spawnCategory);
            }

            if (!spawnThisTick || limit == 0) {
                continue;
            }

            if ((spawnFriendlies || !mobCategory.isFriendly()) && (spawnEnemies || mobCategory.isFriendly()) && (spawnPersistent || !mobCategory.isPersistent()) && canSpawnForCategoryGlobal(state, mobCategory, limit)) { // Paper - Optional per player mob spawns; remove global check, check later during the local one
                // CraftBukkit end
                spawningCategories.add(mobCategory);
            }
        }

        return spawningCategories;
    }

    // CraftBukkit start - SpawnState.canSpawnForCategoryGlobal(mobCategory, limit)
    private static boolean canSpawnForCategoryGlobal(final NaturalSpawner.SpawnState state, final MobCategory mobCategory, final int limit) {
        final NaturalSpawner_SpawnStateAccessorMixin accessor = (NaturalSpawner_SpawnStateAccessorMixin) state;
        int maxMobCount = limit * accessor.fission$spawnableChunkCount() / MAGIC_NUMBER;
        // CraftBukkit end
        return accessor.fission$mobCategoryCounts().getInt(mobCategory) < maxMobCount;
    }

    // Paper start - don't load chunks for mob spawn
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState paper$startStateIfLoaded(final ChunkAccess chunk, final BlockPos start, final Operation<BlockState> original, @Local(argsOnly = true) final ServerLevel level) {
        return level.getBlockStateIfLoadedAndInBounds(start);
    }

    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isRedstoneConductor(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean paper$nullStartState(final BlockState state, final BlockGetter level, final BlockPos pos, final Operation<Boolean> original) {
        return state == null || original.call(state, level, pos); // Paper - state != null && !conductor
    }

    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;isRightDistanceToPlayerAndSpawnPoint(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos$MutableBlockPos;D)Z"))
    private static boolean paper$posLoaded(final ServerLevel level, final ChunkAccess chunk, final BlockPos.MutableBlockPos pos, final double distance, final Operation<Boolean> original) {
        return level.isLoadedAndInBounds(pos) && original.call(level, chunk, pos, distance);
    }
    // Paper end - don't load chunks for mob spawn

    // Paper start - PreCreatureSpawnEvent
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;isValidSpawnPostitionForType(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/MobSpawnSettings$SpawnerData;Lnet/minecraft/core/BlockPos$MutableBlockPos;D)Z"))
    private static boolean paper$preCreatureSpawnEvent(final ServerLevel level, final MobCategory mobCategory, final StructureManager structureManager, final ChunkGenerator generator,
                                                       final MobSpawnSettings.SpawnerData currentSpawnData, final BlockPos.MutableBlockPos pos, final double nearestPlayerDistanceSqr,
                                                       final Operation<Boolean> original, @Share("paperAbortSpawn") final LocalBooleanRef abort) {
        EntityType<?> type = currentSpawnData.type();
        com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event = new com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent(
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level),
                org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(type), org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL
        );
        if (!event.callEvent()) {
            if (event.shouldAbortSpawn()) {
                abort.set(true); // PreSpawnStatus.ABORT
            }
            return false; // PreSpawnStatus.CANCELLED
        }
        return original.call(level, mobCategory, structureManager, generator, currentSpawnData, pos, nearestPlayerDistanceSqr);
    }

    // FissionMC: PreSpawnStatus.ABORT returns from spawnCategoryForPosition; checked when the next spawn attempt starts
    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I", ordinal = 0), cancellable = true)
    private static void paper$abortSpawn(final CallbackInfo ci, @Share("paperAbortSpawn") final LocalBooleanRef abort) {
        if (abort.get()) {
            ci.cancel();
        }
    }
    // Paper end - PreCreatureSpawnEvent

    // CraftBukkit start
    // SPIGOT-7045: Give ocelot babies back their special spawn reason. Note: This is the only modification required as ocelots count as monsters which means they only spawn during normal chunk ticking and do not spawn during chunk generation as starter mobs.
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private static void paper$naturalSpawnReason(final ServerLevel level, final Entity mob, final Operation<Void> original) {
        level.addFreshEntityWithPassengers(mob, (mob instanceof net.minecraft.world.entity.animal.feline.Ocelot && !((org.bukkit.entity.Ageable) mob.getBukkitEntity()).isAdult()) ? org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.OCELOT_BABY : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
    }

    // TODO(behaviour): Paper also skips clusterSize/groupSize++ when the spawn was cancelled (the increments precede the add in bytecode)
    @WrapWithCondition(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;run(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"))
    private static boolean paper$callbackIfSpawned(final NaturalSpawner.AfterSpawnCallback callback, final Mob mob, final ChunkAccess chunk) {
        return !mob.isRemoved();
    }

    @WrapOperation(method = "spawnMobsForChunkGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private static void paper$chunkGenSpawnReason(final ServerLevelAccessor level, final Entity mob, final Operation<Void> original) {
        level.addFreshEntityWithPassengers(mob, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CHUNK_GEN); // CraftBukkit
    }
    // CraftBukkit end
}
