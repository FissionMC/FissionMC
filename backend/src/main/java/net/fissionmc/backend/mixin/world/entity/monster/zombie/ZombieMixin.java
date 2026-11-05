package net.fissionmc.backend.mixin.world.entity.monster.zombie;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.LevelEvent;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/monster/zombie/Zombie.java.patch */
@Mixin(Zombie.class)
public abstract class ZombieMixin {
    private boolean shouldBurnInDay = true; // Paper - Add more Zombie API
    // Done: infection conversion delegate, reinforcement target/spawn reasons, EntityCombustByEntityEvent. TODO(behaviour): "Paper.ShouldBurnInDay" save/load, ConversionTracker event reasons (drowning), zombie turtle egg/villager target config.

    @Inject(method = "isSunSensitive", at = @At("HEAD"), cancellable = true)
    private void fission$isSunSensitive(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.shouldBurnInDay); // Paper - Add more Zombie API
    }

    // Paper start - Add more Zombie API
    public void setShouldBurnInDay(boolean shouldBurnInDay) {
        this.shouldBurnInDay = shouldBurnInDay;
    }
    // Paper end - Add more Zombie API

    // CraftBukkit start
    // Surface: public static (see surface/members/entitytypes.txt); the loader widens it.
    private static @Nullable ZombieVillager convertVillagerToZombieVillager(final ServerLevel level, final Villager villager, final BlockPos blockPosition, boolean silent, org.bukkit.event.entity.EntityTransformEvent.TransformReason transformReason, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason creatureSpawnReason) {
        // CraftBukkit end
        // Paper start - Clear display items before conversion
        for (net.minecraft.world.entity.ai.behavior.BehaviorControl<? super Villager> behavior : villager.getBrain().getRunningBehaviors()) {
            if (behavior instanceof net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer) {
                behavior.doStop(level, villager, level.getGameTime());
            }
        }
        // Paper end - Clear display items before conversion
        ZombieVillager zombieVillager = villager.convertTo(
            EntityTypes.ZOMBIE_VILLAGER,
            ConversionParams.single(villager, true, true),
            zombie -> {
                zombie.setVillagerDataFinalized(villager.getVillagerDataFinalized());
                zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()), EntitySpawnReason.CONVERSION, new Zombie.ZombieGroupData(false, true));
                zombie.setVillagerData(villager.getVillagerData());
                zombie.setGossips(villager.getGossips().copy());
                zombie.setTradeOffers(villager.getOffers().copy());
                zombie.setVillagerXp(villager.getVillagerXp());
                // CraftBukkit start
                if (!silent) {
                    level.levelEvent(null, LevelEvent.SOUND_ZOMBIE_INFECTED, blockPosition, 0);
                }
            }, transformReason, creatureSpawnReason);
        return zombieVillager;
        // CraftBukkit end
    }

    // vanilla convertVillagerToZombieVillager(level, villager) delegates to the static overload with INFECTION reasons
    @Inject(method = "convertVillagerToZombieVillager(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;)Z", at = @At("HEAD"), cancellable = true)
    private void fission$convertDelegate(final ServerLevel level, final Villager villager, final CallbackInfoReturnable<Boolean> cir) {
        final Zombie self = (Zombie) (Object) this;
        cir.setReturnValue(convertVillagerToZombieVillager(level, villager, self.blockPosition(), self.isSilent(), org.bukkit.event.entity.EntityTransformEvent.TransformReason.INFECTION, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.INFECTION) != null);
    }

    // reinforcement: target reason + spawn reason
    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/zombie/Zombie;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$reinforcementTarget(final Zombie reinforcement, final LivingEntity target, final Operation<Void> original) {
        reinforcement.setTarget(target, org.bukkit.event.entity.EntityTargetEvent.TargetReason.REINFORCEMENT_TARGET); // CraftBukkit
    }

    @WrapOperation(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"))
    private void fission$reinforcementSpawn(final ServerLevel level, final Entity reinforcement, final Operation<Void> original) {
        level.addFreshEntityWithPassengers(reinforcement, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.REINFORCEMENTS); // CraftBukkit
    }

    // doHurtTarget: EntityCombustByEntityEvent
    @WrapOperation(method = "doHurtTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;igniteForSeconds(F)V"))
    private void fission$combust(final Entity target, final float seconds, final Operation<Void> original) {
        org.bukkit.event.entity.EntityCombustByEntityEvent event = new org.bukkit.event.entity.EntityCombustByEntityEvent(((Entity) (Object) this).getBukkitEntity(), target.getBukkitEntity(), seconds);
        if (event.callEvent()) {
            target.igniteForSeconds(event.getDuration(), false);
        }
    }
}
