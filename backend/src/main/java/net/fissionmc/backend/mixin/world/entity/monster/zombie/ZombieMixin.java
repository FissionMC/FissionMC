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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/monster/zombie/Zombie.java.patch */
@Mixin(Zombie.class)
public abstract class ZombieMixin {
    private boolean shouldBurnInDay = true; // Paper - Add more Zombie API
    // TODO(behaviour): "Paper.ShouldBurnInDay" save/load.

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
    // TODO(behaviour): the vanilla instance convertVillagerToZombieVillager(level, villager) should delegate here (INFECTION reasons).
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
}
