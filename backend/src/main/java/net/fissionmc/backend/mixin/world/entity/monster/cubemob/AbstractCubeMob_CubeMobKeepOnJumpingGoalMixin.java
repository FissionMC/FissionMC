package net.fissionmc.backend.mixin.world.entity.monster.cubemob;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/monster/cubemob/AbstractCubeMob.java.patch (Slime pathfinder events) */
@Mixin(targets = "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob$CubeMobKeepOnJumpingGoal")
public abstract class AbstractCubeMob_CubeMobKeepOnJumpingGoalMixin {
    @Shadow @Final private AbstractCubeMob cubeMob;

    @ModifyReturnValue(method = "canUse", at = @At("RETURN"))
    private boolean paper$wanderEvent(final boolean canUse) {
        return canUse && this.cubeMob.canWander() && new com.destroystokyo.paper.event.entity.SlimeWanderEvent((org.bukkit.entity.AbstractCubeMob) this.cubeMob.getBukkitEntity()).callEvent(); // Paper - Slime pathfinder events
    }
}
