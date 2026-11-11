package net.fissionmc.backend.mixin.world.entity.monster.zombie;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Paper: sources/net/minecraft/world/entity/monster/zombie/ZombifiedPiglin.java.patch
 * done: PigZombieAngerEvent (a cancelled event clears the anger target and stops the HurtByTargetGoal).
 * Not ported: TARGET_ATTACKED_NEARBY_ENTITY target reason in alertOthers, setTarget(target, reason) override.
 */
@Mixin(ZombifiedPiglin.class)
public abstract class ZombifiedPiglinMixin {
    @WrapOperation(method = "startPersistentAngerTimer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/zombie/ZombifiedPiglin;setTimeToRemainAngry(J)V"))
    private void paper$angerEvent(final ZombifiedPiglin self, final long time, final Operation<Void> original) {
        // CraftBukkit start
        net.minecraft.world.entity.Entity entity = EntityReference.getLivingEntity(self.getPersistentAngerTarget(), self.level());
        org.bukkit.event.entity.PigZombieAngerEvent event = new org.bukkit.event.entity.PigZombieAngerEvent((org.bukkit.entity.PigZombie) self.getBukkitEntity(), (entity == null) ? null : entity.getBukkitEntity(), (int) time);
        if (!event.callEvent()) {
            self.setPersistentAngerTarget(null);
            // Paper - fix PigZombieAngerEvent cancellation (Paper keeps the goal in a field)
            for (WrappedGoal goal : self.targetSelector.getAvailableGoals()) {
                if (goal.getGoal() instanceof HurtByTargetGoal hurtByTargetGoal) hurtByTargetGoal.stop();
            }
            return;
        }
        original.call(self, (long) event.getNewAnger());
        // CraftBukkit end
    }
}
