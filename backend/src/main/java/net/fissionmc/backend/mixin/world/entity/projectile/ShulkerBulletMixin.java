package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/projectile/ShulkerBullet.java.patch */
@Mixin(ShulkerBullet.class)
public abstract class ShulkerBulletMixin {
    @Shadow private EntityReference<Entity> finalTarget;
    @Shadow private Direction currentMoveDirection;

    @Shadow protected abstract void selectNextMoveDirection(Direction.Axis invalidAxis, @Nullable Entity target);

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction$Axis;)V", at = @At("TAIL"))
    private void fission$projectileSource(Level level, LivingEntity owner, Entity target, Direction.Axis invalidStartAxis, CallbackInfo ci) {
        ((Entity) (Object) this).projectileSource = owner.getBukkitEntity(); // CraftBukkit
    }

    // CraftBukkit start
    @Nullable
    public Entity getTarget() {
        return EntityReference.get(this.finalTarget, ((Entity) (Object) this).level(), Entity.class);
    }

    public void setTarget(@Nullable Entity finalTarget) {
        this.finalTarget = finalTarget == null ? null : net.minecraft.world.entity.EntityReference.of(finalTarget);
        this.currentMoveDirection = Direction.UP;
        this.selectNextMoveDirection(Direction.Axis.X, finalTarget);
    }
    // CraftBukkit end

    @Redirect(method = "checkDespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;discard()V"))
    private void fission$discardDespawn(ShulkerBullet instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(ShulkerBullet instance, HitResult hitResult) {
        return instance.preHitTargetOrDeflectSelf(hitResult); // CraftBukkit - projectile hit event
    }

    @Redirect(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean fission$levitationCause(LivingEntity target, MobEffectInstance effect, @Nullable Entity source) {
        return target.addEffect(effect, source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK); // CraftBukkit
    }

    // CraftBukkit start - add Bukkit remove cause
    private void fission$destroy(org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        ShulkerBullet self = (ShulkerBullet) (Object) this;
        self.discard(cause);
        // CraftBukkit end
        self.level().gameEvent(GameEvent.ENTITY_DAMAGE, self.position(), GameEvent.Context.of(self));
    }

    @Redirect(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;destroy()V"))
    private void fission$destroyHit(ShulkerBullet instance) {
        this.fission$destroy(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;destroy()V"))
    private void fission$destroyDeath(ShulkerBullet instance) {
        this.fission$destroy(org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void fission$damageEvent(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent((ShulkerBullet) (Object) this, source, damage, false)) {
            cir.setReturnValue(false);
        }
        // CraftBukkit end
    }
}
