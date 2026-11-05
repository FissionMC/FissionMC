package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/ThrowableProjectile.java.patch */
@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;"))
    private ProjectileDeflection fission$preHit(ThrowableProjectile instance, HitResult result) {
        return instance.preHitTargetOrDeflectSelf(result); // CraftBukkit - projectile hit event
    }
}
