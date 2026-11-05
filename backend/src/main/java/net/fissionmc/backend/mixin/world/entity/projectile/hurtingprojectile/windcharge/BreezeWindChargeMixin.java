package net.fissionmc.backend.mixin.world.entity.projectile.hurtingprojectile.windcharge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/windcharge/BreezeWindCharge.java.patch */
@Mixin(BreezeWindCharge.class)
public abstract class BreezeWindChargeMixin {
    // Paper start - Fire event for WindCharge explosions
    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V"))
    private void fission$explosionPrime(Level level, Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator, double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction, ParticleOptions small, ParticleOptions large, WeightedList<?> blockParticles, Holder<SoundEvent> sound, Operation<Void> original) {
        org.bukkit.event.entity.ExplosionPrimeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent((BreezeWindCharge) (Object) this, radius, fire);
        if (event.isCancelled()) {
            return;
        }
        original.call(level, source, damageSource, calculator, x, y, z, event.getRadius(), event.getFire(), interaction, small, large, blockParticles, sound);
    }
    // Paper end - Fire event for WindCharge explosions
}
