package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/WitherSkull.java.patch */
@Mixin(WitherSkull.class)
public abstract class WitherSkullMixin {
    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;heal(F)V"))
    private void fission$healWither(LivingEntity owner, float amount) {
        owner.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.WITHER); // CraftBukkit
    }

    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSources;magic()Lnet/minecraft/world/damagesource/DamageSource;"))
    private DamageSource fission$magicDamager(DamageSources sources) {
        return sources.magic().eventEntityDamager((WitherSkull) (Object) this); // Paper - Fire EntityDamageByEntityEvent for unowned wither skulls // Paper - fix DamageSource API
    }

    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$witherCause(LivingEntity entity, MobEffectInstance effect, Entity source) {
        return entity.addEffect(effect, source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK); // CraftBukkit
    }

    @WrapOperation(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"))
    private void fission$explosionPrime(Level level, Entity source, double x, double y, double z, float r, boolean fire, Level.ExplosionInteraction interaction, Operation<Void> original) {
        // CraftBukkit start
        org.bukkit.event.entity.ExplosionPrimeEvent event = new org.bukkit.event.entity.ExplosionPrimeEvent(((WitherSkull) (Object) this).getBukkitEntity(), r, fire);
        if (event.callEvent()) {
            original.call(level, source, x, y, z, event.getRadius(), event.getFire(), interaction);
        }
        // CraftBukkit end
    }

    @Redirect(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/WitherSkull;discard()V"))
    private void fission$discardHit(WitherSkull instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }
}
