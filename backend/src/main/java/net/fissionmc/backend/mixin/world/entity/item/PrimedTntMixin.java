package net.fissionmc.backend.mixin.world.entity.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: paper-server/patches/sources/net/minecraft/world/entity/item/PrimedTnt.java.patch
 * left (config, defaults = vanilla): Spigot maxTntTicksPerTick, tntEntityHeightNerf, preventTntFromMovingInWater.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    private boolean isIncendiary = false; // CraftBukkit

    // CraftBukkit start - Need to reverse the order of the explosion and the entity death so we have a location for the event
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/PrimedTnt;discard()V"))
    private void fission$noEarlyDiscard(PrimedTnt self, Operation<Void> original) {
        //this.discard();
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/PrimedTnt;explode()V"))
    private void fission$explodeThenDiscard(PrimedTnt self, Operation<Void> original) {
        original.call(self);
        self.discard(EntityRemoveEvent.Cause.EXPLODE); // CraftBukkit - add Bukkit remove cause
    }
    // CraftBukkit end

    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"))
    private void fission$explodePrime(Level level, Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator, double x, double y, double z, float power, boolean fire, Level.ExplosionInteraction interaction, Operation<Void> original) {
        // CraftBukkit start
        ExplosionPrimeEvent event = CraftEventFactory.callExplosionPrimeEvent((org.bukkit.entity.Explosive) ((PrimedTnt) (Object) this).getBukkitEntity());
        if (event.isCancelled()) {
            return;
        }
        // CraftBukkit end
        original.call(level, source, damageSource, calculator, x, y, z, event.getRadius(), event.getFire(), interaction); // CraftBukkit
    }
}
