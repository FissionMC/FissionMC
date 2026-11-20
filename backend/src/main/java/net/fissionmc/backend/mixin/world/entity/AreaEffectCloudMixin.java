package net.fissionmc.backend.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/entity/AreaEffectCloud.java.patch
 * done: AreaEffectCloudApplyEvent (candidates are pre-filtered like Paper's first loop and the listener edited list replaces the entity list), AREA_EFFECT_CLOUD potion cause.
 * Difference: radiusOnUse shrinkage inside the loop still affects later entities (Paper collects them with the initial radius first).
 */
@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudMixin {
    @Shadow @Final private java.util.Map<Entity, Integer> victims;

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<LivingEntity> paper$applyEvent(final Level level, final Class<LivingEntity> type, final AABB box, final Operation<List<LivingEntity>> original,
                                                @Local(name = "allEffects") final List<MobEffectInstance> allEffects, @Local(name = "radius") final float radius) {
        final List<LivingEntity> entities = original.call(level, type, box);
        if (entities.isEmpty()) {
            return entities;
        }
        final AreaEffectCloud self = (AreaEffectCloud) (Object) this;
        // CraftBukkit start
        final List<org.bukkit.entity.LivingEntity> bukkitEntities = new ArrayList<>();
        for (final LivingEntity entity : entities) {
            if (!this.victims.containsKey(entity) && entity.isAffectedByPotions() && !allEffects.stream().noneMatch(entity::canBeAffected)) {
                final double xd = entity.getX() - self.getX();
                final double zd = entity.getZ() - self.getZ();
                final double dist = xd * xd + zd * zd;
                if (dist <= radius * radius) {
                    bukkitEntities.add(entity.getBukkitEntity());
                }
            }
        }
        final org.bukkit.event.entity.AreaEffectCloudApplyEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callAreaEffectCloudApplyEvent(self, bukkitEntities);
        if (event.isCancelled()) {
            return List.of();
        }
        final List<LivingEntity> affected = new ArrayList<>();
        for (final org.bukkit.entity.LivingEntity bukkitLivingEntity : event.getAffectedEntities()) {
            if (bukkitLivingEntity instanceof org.bukkit.craftbukkit.entity.CraftLivingEntity craftLivingEntity) {
                affected.add(craftLivingEntity.getHandle());
            }
        }
        return affected;
        // CraftBukkit end
    }

    @WrapOperation(method = "serverTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean paper$effectCause(final LivingEntity entity, final MobEffectInstance effect, final Entity source, final Operation<Boolean> original) {
        return entity.addEffect(effect, source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD); // CraftBukkit
    }
}
