package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/monster/cubemob/AbstractCubeMob.java.patch (Slime pathfinder events) */
@Mixin(targets = "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobAttackGoal")
public abstract class AbstractCubeMob_CubeMobAttackGoalMixin {
    @Shadow @Final private AbstractCubeMob cubeMob;
    @Shadow private int growTiredTimer;

    // Paper start - Slime pathfinder events
    @ModifyReturnValue(method = {"canUse", "canContinueToUse"}, at = @At("RETURN"))
    private boolean paper$targetEvent(final boolean canUse) {
        if (!canUse) {
            return false;
        }
        final net.minecraft.world.entity.LivingEntity target = this.cubeMob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return this.cubeMob.canWander() && new com.destroystokyo.paper.event.entity.SlimeTargetLivingEntityEvent((org.bukkit.entity.AbstractCubeMob) this.cubeMob.getBukkitEntity(), target.getBukkitEntity()).callEvent();
    }

    // clear timer and target when goal resets
    public void stop() {
        this.growTiredTimer = 0;
        this.cubeMob.setTarget(null);
    }
    // Paper end - Slime pathfinder events
}
