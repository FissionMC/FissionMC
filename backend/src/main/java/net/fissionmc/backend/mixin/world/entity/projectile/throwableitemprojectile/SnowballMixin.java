package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/Snowball.java.patch */
@Mixin(Snowball.class)
public abstract class SnowballMixin {
    @Redirect(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/Snowball;discard()V"))
    private void fission$discardHit(Snowball instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }
}
