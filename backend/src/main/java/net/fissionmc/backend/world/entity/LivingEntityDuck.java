package net.fissionmc.backend.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;

/** Implemented by LivingEntity through LivingEntityMixin; lets mixins on subclasses reach Paper's damage pipeline. */
public interface LivingEntityDuck {
    /**
     * If an EntityDamageEvent is pending (vanilla actuallyHurt reached from Paper's hurtServer), runs Paper's
     * actuallyHurt(level, source, dmg, event) body and returns true; otherwise returns false.
     */
    boolean fission$paperActuallyHurtPending(ServerLevel level, DamageSource source, float dmg);
}
