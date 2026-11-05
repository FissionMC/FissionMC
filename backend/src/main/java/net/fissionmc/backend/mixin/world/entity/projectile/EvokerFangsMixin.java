package net.fissionmc.backend.mixin.world.entity.projectile;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.projectile.EvokerFangs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Paper: sources/net/minecraft/world/entity/projectile/EvokerFangs.java.patch */
@Mixin(EvokerFangs.class)
public abstract class EvokerFangsMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/EvokerFangs;discard()V"))
    private void fission$discardDespawn(EvokerFangs instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
    }

    @Redirect(method = "dealDamageTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSources;magic()Lnet/minecraft/world/damagesource/DamageSource;"))
    private DamageSource fission$magicDamager(DamageSources sources) {
        return sources.magic().eventEntityDamager((EvokerFangs) (Object) this); // CraftBukkit
    }
}
