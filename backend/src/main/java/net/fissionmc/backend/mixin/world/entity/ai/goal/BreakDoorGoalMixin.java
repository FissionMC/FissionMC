package net.fissionmc.backend.mixin.world.entity.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/ai/goal/BreakDoorGoal.java.patch (event; MC-263999 block state fix not ported) */
@Mixin(BreakDoorGoal.class)
public abstract class BreakDoorGoalMixin extends net.minecraft.world.entity.ai.goal.DoorInteractGoal {
    protected BreakDoorGoalMixin(final Mob mob) {
        super(mob);
    }

    @Shadow public abstract void start();

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), cancellable = true)
    private void fission$breakDoorEvent(CallbackInfo ci) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityBreakDoorEvent(this.mob, this.doorPos, this.mob.level().getFluidState(this.doorPos).createLegacyBlock()).isCancelled()) { // Paper - fix wrong block state
            this.start();
            ci.cancel();
        }
        // CraftBukkit end
    }
}
