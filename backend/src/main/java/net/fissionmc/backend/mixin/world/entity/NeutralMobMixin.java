package net.fissionmc.backend.mixin.world.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/entity/NeutralMob.java.patch */
@Mixin(NeutralMob.class)
public interface NeutralMobMixin {
    @Shadow EntityReference<LivingEntity> getPersistentAngerTarget();

    @org.spongepowered.asm.mixin.injection.Redirect(method = "readPersistentAngerSaveData", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/NeutralMob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$deferInitialAnger(NeutralMob self, LivingEntity target, Level level) {
        // Paper - Prevent entity loading causing async lookups; Moved diff to separate method
        // If this entity already survived its first tick, e.g. is loaded and ticked in sync, actively
        // tick the initial persistent anger.
        // If not, let the first tick on the baseTick call the method later down the line.
        if (this instanceof Entity entity && !((EntityAccessorMixin) entity).fission$isFirstTick()) this.tickInitialPersistentAnger(level);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "stopBeingAngry", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/entity/NeutralMob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$forgotTarget(NeutralMob self, LivingEntity target) {
        ((Mob) (Object) this).setTarget(null, org.bukkit.event.entity.EntityTargetEvent.TargetReason.FORGOT_TARGET); // CraftBukkit
    }

    // Paper start - Prevent entity loading causing async lookups
    // Update last hurt when ticking
    default void tickInitialPersistentAnger(Level level) {
        LivingEntity target = EntityReference.getLivingEntity(this.getPersistentAngerTarget(), level);
        if (target != null) {
            ((Mob) (Object) this).setTarget(target, null); // FissionMC: NeutralMob#setTarget(target, reason) is implemented by Mob
        }
    }
    // Paper end - Prevent entity loading causing async lookups
}
