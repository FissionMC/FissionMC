package net.fissionmc.backend.mixin.world.entity.ai.goal;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/ai/goal/RunAroundLikeCrazyGoal.java.patch (EntityTameEvent behind the temper roll; a cancelled event fails the roll) */
@Mixin(RunAroundLikeCrazyGoal.class)
public abstract class RunAroundLikeCrazyGoalMixin {
    @Shadow @Final private AbstractHorse horse;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I", ordinal = 1))
    private int paper$tameEvent(final int roll, @Local(name = "temper") final int temper, @Local(name = "player") final Player player) {
        if (roll < temper && org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTameEvent(this.horse, player).isCancelled()) { // CraftBukkit - fire EntityTameEvent
            return Integer.MAX_VALUE;
        }
        return roll;
    }
}
