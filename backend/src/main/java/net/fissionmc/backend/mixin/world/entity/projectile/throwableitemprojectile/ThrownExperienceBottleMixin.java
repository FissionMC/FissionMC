package net.fissionmc.backend.mixin.world.entity.projectile.throwableitemprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownExperienceBottle.java.patch */
@Mixin(ThrownExperienceBottle.class)
public abstract class ThrownExperienceBottleMixin extends ThrowableItemProjectile {
    protected ThrownExperienceBottleMixin(final EntityType<? extends ThrowableItemProjectile> type, final Level level) {
        super(type, level);
    }

    // FissionMC: replaced body (ExpBottleEvent, orb spawn reason, effects after the event)
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void fission$onHit(HitResult hitResult, CallbackInfo ci) {
        ci.cancel();
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel level) {
            Runnable afterEvent = () -> { // Paper - delay after event
            level.levelEvent(LevelEvent.PARTICLES_SPELL_POTION_SPLASH, this.blockPosition(), -13083194);
            if (!this.isSilent()) {
                level.levelEvent(LevelEvent.SOUND_SPELL_POTION_SPLASH, this.blockPosition(), 0);
            }
            }; // Paper - delay after event

            int xpCount = 3 + this.random.nextInt(5) + this.random.nextInt(5);
            // Paper start - exp bottle event
            org.bukkit.event.entity.ExpBottleEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExpBottleEvent(this, hitResult, xpCount);
            xpCount = event.getExperience();
            // Paper end - exp bottle event
            if (hitResult instanceof BlockHitResult blockHitResult) {
                Vec3 blockNormalHit = blockHitResult.getDirection().getUnitVec3();
                ExperienceOrb.awardWithDirection(level, hitResult.getLocation(), blockNormalHit, xpCount, org.bukkit.entity.ExperienceOrb.SpawnReason.EXP_BOTTLE, this.getOwner(), this); // Paper
            } else {
                ExperienceOrb.awardWithDirection(level, hitResult.getLocation(), this.getDeltaMovement().scale(-1.0), xpCount, org.bukkit.entity.ExperienceOrb.SpawnReason.EXP_BOTTLE, this.getOwner(), this); // Paper
            }
            // Paper start - exp bottle event
            if (event.getShowEffect()) {
                afterEvent.run();
            }
            // Paper end - exp bottle event

            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }
}
