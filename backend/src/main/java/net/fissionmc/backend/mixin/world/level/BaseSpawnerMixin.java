package net.fissionmc.backend.mixin.world.level;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/level/BaseSpawner.java.patch
 * left: PreSpawnerSpawnEvent (inside the spawn loop), tick-rate config (default = vanilla), "impossible spawn tick" early return,
 * Paper.Delay/MinSpawnDelay/MaxSpawnDelay int save/load, spawnPotentials reset (SPIGOT-3496).
 */
@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
    @WrapOperation(method = "isNearPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasNearbyAlivePlayer(DDDD)Z"))
    private boolean paper$affectsSpawning(final Level level, final double x, final double y, final double z, final double range, final Operation<Boolean> original) {
        // Paper - Affects Spawning API (Level#hasNearbyAlivePlayerThatAffectsSpawning)
        for (net.minecraft.world.entity.player.Player player : level.players()) {
            if (net.minecraft.world.entity.EntitySelector.NO_SPECTATORS.test(player) && player.isAlive() && player.affectsSpawning) {
                double dist = player.distanceToSqr(x, y, z);
                if (range < 0.0D || dist < range * range) {
                    return true;
                }
            }
        }
        return false;
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean paper$spawnerSpawn(final ServerLevel level, final Entity entity, final Operation<Boolean> original, @Local(argsOnly = true) final BlockPos pos) {
        // Spigot start
        if (entity instanceof Mob mob && mob.level().spigotConfig.nerfSpawnerMobs) {
            mob.aware = false;
        }
        // Spigot end
        // Paper start
        entity.spawnedViaMobSpawner = true;
        entity.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
        // FissionMC: Paper `continue`s the spawn loop on a cancelled SpawnerSpawnEvent (delay = true); here the vanilla failure path
        // (delay + stop this tick's remaining attempts) is taken instead.
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callSpawnerSpawnEvent(entity, pos).isCancelled()) {
            return false;
        }
        return level.tryAddFreshEntityWithPassengers(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER);
        // Paper end
    }
}
