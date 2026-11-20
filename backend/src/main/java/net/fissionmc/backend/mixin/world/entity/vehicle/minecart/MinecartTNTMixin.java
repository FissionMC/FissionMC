package net.fissionmc.backend.mixin.world.entity.vehicle.minecart;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/entity/vehicle/minecart/MinecartTNT.java.patch
 * done: EntityIgniteEvent (primeFuse with fuse time), ExplosionPrimeEvent in explode (radius and fire from the event; a cancelled event keeps the fuse disarmed).
 * Not ported: tnt height nerf, EntityRemoveEvent causes.
 */
@Mixin(MinecartTNT.class)
public abstract class MinecartTNTMixin {
    // Surface: public (see surface/members/entitytypes.txt); the loader widens it.
    private boolean isIncendiary = false; // CraftBukkit

    @Shadow private int fuse;
    @Shadow private float explosionPowerBase;
    @Shadow private float explosionSpeedFactor;
    @Shadow private @Nullable DamageSource ignitionSource;

    @Unique private org.bukkit.event.entity.ExplosionPrimeEvent fission$primeEvent;

    // Paper start - add fuseTime param
    @Inject(method = "primeFuse(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true)
    private void fission$primeDefault(final @Nullable DamageSource source, final CallbackInfo ci) {
        ci.cancel();
        this.primeFuse(source, 80);
    }

    public void primeFuse(final @Nullable DamageSource source, int fuseTime) {
        final MinecartTNT self = (MinecartTNT) (Object) this;
        // Paper end - add fuseTime param
        if (!(self.level() instanceof ServerLevel serverLevel && !serverLevel.getGameRules().get(GameRules.TNT_EXPLODES))) {
            // Paper start - call EntityIgniteEvent
            fuseTime = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityIgniteEvent(self, fuseTime);
            if (fuseTime == net.minecraft.world.entity.item.PrimedTnt.NO_FUSE) {
                return;
            }
            this.fuse = fuseTime;
            // Paper end - call EntityIgniteEvent
            if (!self.level().isClientSide()) {
                if (source != null && this.ignitionSource == null) {
                    this.ignitionSource = self.damageSources().explosion(self, source.getEntity());
                }

                self.level().broadcastEntityEvent(self, (byte) 70);
                if (!self.isSilent()) {
                    self.level().playSound(null, self.getX(), self.getY(), self.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
            }
        }
    }

    // Paper - call EntityIgniteEvent: destroy primes with the random fuse directly instead of overwriting it afterwards
    @WrapOperation(method = "destroy(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/minecart/MinecartTNT;primeFuse(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void fission$destroyPrime(final MinecartTNT self, final DamageSource source, final Operation<Void> original) {
        self.primeFuse(source, self.getRandom().nextInt(20) + self.getRandom().nextInt(20));
    }

    @WrapOperation(method = "destroy(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/vehicle/minecart/MinecartTNT;fuse:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void fission$destroyNoOverwrite(final MinecartTNT self, final int value, final Operation<Void> original) {
    }

    // CraftBukkit start - explosion prime event
    @Inject(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"), cancellable = true)
    private void fission$explosionPrime(final @Nullable DamageSource damageSource, final double speedSqr, final CallbackInfo ci) {
        final MinecartTNT self = (MinecartTNT) (Object) this;
        final double speed = Math.min(Math.sqrt(speedSqr), 5.0);
        final org.bukkit.event.entity.ExplosionPrimeEvent event = new org.bukkit.event.entity.ExplosionPrimeEvent(
            self.getBukkitEntity(),
            (float) (this.explosionPowerBase + this.explosionSpeedFactor * self.getRandom().nextDouble() * 1.5 * speed),
            this.isIncendiary
        );
        if (!event.callEvent()) {
            this.fuse = net.minecraft.world.entity.item.PrimedTnt.NO_FUSE;
            ci.cancel();
            return;
        }
        this.fission$primeEvent = event;
    }

    @ModifyArg(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"), index = 6)
    private float fission$primeRadius(final float radius) {
        return this.fission$primeEvent != null ? this.fission$primeEvent.getRadius() : radius;
    }

    @ModifyArg(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"), index = 7)
    private boolean fission$primeFire(final boolean fire) {
        final org.bukkit.event.entity.ExplosionPrimeEvent event = this.fission$primeEvent;
        this.fission$primeEvent = null;
        return event != null ? event.getFire() : fire;
    }
    // CraftBukkit end
}
