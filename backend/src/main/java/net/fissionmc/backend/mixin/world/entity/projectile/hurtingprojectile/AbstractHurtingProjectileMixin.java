package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile;

import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile.java.patch */
@Mixin(AbstractHurtingProjectile.class)
public abstract class AbstractHurtingProjectileMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens them.
    private float bukkitYield = 1; // CraftBukkit
    private boolean isIncendiary = true; // CraftBukkit

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(AbstractHurtingProjectile instance, HitResult hitResult) {
        return instance.preHitTargetOrDeflectSelf(hitResult); // CraftBukkit - projectile hit event
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile;discard()V"))
    private void fission$discardDespawn(AbstractHurtingProjectile instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }
}
