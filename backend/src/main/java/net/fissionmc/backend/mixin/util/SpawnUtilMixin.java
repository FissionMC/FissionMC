package net.fissionmc.backend.mixin.util;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.SpawnUtil;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/util/SpawnUtil.java.patch (PreCreatureSpawnEvent, spawn reason) */
@Mixin(SpawnUtil.class)
public abstract class SpawnUtilMixin {
    @Shadow
    private static boolean moveToPossibleSpawnPosition(final ServerLevel level, final int spawnRangeY, final BlockPos.MutableBlockPos searchPos, final SpawnUtil.Strategy strategy) {
        throw new AssertionError();
    }

    // CraftBukkit start - vanilla overload delegates with SpawnReason.DEFAULT
    @Inject(method = "trySpawnMob(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;IIILnet/minecraft/util/SpawnUtil$Strategy;Z)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private static <T extends Mob> void craftbukkit$delegate(final EntityType<T> entityType, final EntitySpawnReason spawnReason, final ServerLevel level, final BlockPos start,
                                                           final int spawnAttempts, final int spawnRangeXZ, final int spawnRangeY, final SpawnUtil.Strategy strategy, final boolean checkCollisions,
                                                           final CallbackInfoReturnable<Optional<T>> cir) {
        cir.setReturnValue(trySpawnMob(entityType, spawnReason, level, start, spawnAttempts, spawnRangeXZ, spawnRangeY, strategy, checkCollisions, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT, null)); // Paper - pre creature spawn event
    }

    // Surface: public static. Mixin requires non-public statics; the loader widens it.
    private static <T extends Mob> Optional<T> trySpawnMob(
        final EntityType<T> entityType,
        final EntitySpawnReason spawnReason,
        final ServerLevel level,
        final BlockPos start,
        final int spawnAttempts,
        final int spawnRangeXZ,
        final int spawnRangeY,
        final SpawnUtil.Strategy strategy,
        final boolean checkCollisions,
        final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason,
        final @org.jspecify.annotations.Nullable Runnable onAbort // Paper - pre creature spawn event
    ) {
        // CraftBukkit end
        BlockPos.MutableBlockPos searchPos = start.mutable();
        RandomSource random = level.getRandom();

        for (int i = 0; i < spawnAttempts; i++) {
            int dx = Mth.randomBetweenInclusive(random, -spawnRangeXZ, spawnRangeXZ);
            int dz = Mth.randomBetweenInclusive(random, -spawnRangeXZ, spawnRangeXZ);
            searchPos.setWithOffset(start, dx, spawnRangeY, dz);
            if (level.getWorldBorder().isWithinBounds(searchPos)
                && moveToPossibleSpawnPosition(level, spawnRangeY, searchPos, strategy)
                && (!checkCollisions || level.noCollision(entityType.getSpawnAABB(searchPos.getX() + 0.5, searchPos.getY(), searchPos.getZ() + 0.5)))) {
                // Paper start - PreCreatureSpawnEvent
                final com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event = new com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent(
                    org.bukkit.craftbukkit.util.CraftLocation.toBukkit(start, level),
                    org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(entityType),
                    reason
                );
                if (!event.callEvent()) {
                    if (event.shouldAbortSpawn()) {
                        if (onAbort != null) {
                            onAbort.run();
                        }
                        return Optional.empty();
                    }
                    break;
                }
                // Paper end - PreCreatureSpawnEvent
                T mob = (T) entityType.create(level, null, searchPos, spawnReason, false, false);
                if (mob != null) {
                    if (mob.checkSpawnRules(level, spawnReason) && mob.checkSpawnObstruction(level)) {
                        level.addFreshEntityWithPassengers(mob, reason); // CraftBukkit
                        if (mob.isRemoved()) return Optional.empty(); // CraftBukkit
                        mob.playAmbientSound();
                        return Optional.of(mob);
                    }

                    mob.discard(null); // CraftBukkit - add Bukkit remove cause
                }
            }
        }

        return Optional.empty();
    }
}
