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
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball.java.patch */
@Mixin(LargeFireball.class)
public abstract class LargeFireballMixin extends Fireball {
    @Shadow private int explosionPower;

    protected LargeFireballMixin(final EntityType<? extends Fireball> type, final Level level) {
        super(type, level);
    }

    @Inject(method = "<init>*", at = @At("TAIL"))
    private void fission$incendiary(CallbackInfo ci) {
        this.isIncendiary = (this.level() instanceof ServerLevel serverLevel) && serverLevel.getGameRules().get(GameRules.MOB_GRIEFING); // CraftBukkit
    }

    @WrapOperation(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)V"))
    private void fission$explosionPrime(Level level, Entity source, double x, double y, double z, float r, boolean fire, Level.ExplosionInteraction interaction, Operation<Void> original) {
        // CraftBukkit start - fire ExplosionPrimeEvent
        org.bukkit.event.entity.ExplosionPrimeEvent event = new org.bukkit.event.entity.ExplosionPrimeEvent((org.bukkit.entity.Explosive) this.getBukkitEntity());
        if (event.callEvent()) {
            original.call(level, source, x, y, z, event.getRadius(), event.getFire(), interaction);
        }
        // CraftBukkit end - fire ExplosionPrimeEvent
    }

    @Redirect(method = "onHit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball;discard()V"))
    private void fission$discardHit(LargeFireball instance) {
        instance.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void fission$yield(ValueInput input, CallbackInfo ci) {
        this.bukkitYield = this.explosionPower; // CraftBukkit - set bukkitYield when setting explosionPower
    }
}
