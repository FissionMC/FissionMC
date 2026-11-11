package net.fissionmc.backend.mixin.world.entity.monster.zombie;

import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/monster/zombie/ZombieVillager.java.patch */
@Mixin(ZombieVillager.class)
public abstract class ZombieVillagerMixin {
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_CONVERTING_ID;
    @Shadow private int villagerConversionTime;
    @Shadow private UUID conversionStarter;

    // TODO(behaviour): vanilla startConverting(UUID, int) should delegate to startConverting(player, time, true).

    // Paper start - missing entity behaviour api - converting without entity event
    public void startConverting(@Nullable UUID player, int time, boolean broadcastEntityEvent) {
        ZombieVillager self = (ZombieVillager) (Object) this;
        this.conversionStarter = player;
        this.villagerConversionTime = time;
        self.getEntityData().set(ZombieVillagerMixin.DATA_CONVERTING_ID, true);
        // CraftBukkit start
        self.removeEffect(MobEffects.WEAKNESS, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.CONVERSION);
        self.addEffect(new MobEffectInstance(MobEffects.STRENGTH, time, Math.min(self.level().getDifficulty().getId() - 1, 0)), org.bukkit.event.entity.EntityPotionEffectEvent.Cause.CONVERSION);
        // CraftBukkit end
        if (broadcastEntityEvent) self.level().broadcastEntityEvent(self, EntityEvent.ZOMBIE_CONVERTING); // Paper - missing entity behaviour api - converting without entity event
    }
    // Paper end - missing entity behaviour api - converting without entity event

    // CraftBukkit start - CURED reasons; SPIGOT-5208: End conversion to stop event spam
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "finishConversion", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/zombie/ZombieVillager;convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;"))
    private net.minecraft.world.entity.Mob paper$curedReasons(final ZombieVillager self, final net.minecraft.world.entity.EntityType<?> type, final net.minecraft.world.entity.ConversionParams params, final net.minecraft.world.entity.ConversionParams.AfterConversion<?> afterConversion, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.entity.Mob> original) {
        final net.minecraft.world.entity.Mob converted = net.fissionmc.backend.blocks.PaperContext.convert(self, type, params, afterConversion, org.bukkit.event.entity.EntityTransformEvent.TransformReason.CURED, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CURED);
        if (converted == null) {
            ((org.bukkit.entity.ZombieVillager) self.getBukkitEntity()).setConversionTime(-1); // SPIGOT-5208: End conversion to stop event spam
        }
        return converted;
    }
    // CraftBukkit end
}
