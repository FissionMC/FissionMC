package net.fissionmc.backend.mixin.world.entity.ai.goal;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/ai/goal/EatBlockGoal.java.patch (EntityChangeBlockEvent replaces the mobGriefing check) */
@Mixin(EatBlockGoal.class)
public abstract class EatBlockGoalMixin {
    @Shadow @Final private Mob mob;
    @Shadow @Final private Level level;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z", ordinal = 0))
    private boolean fission$eatGrassEvent(boolean mobGriefing, @Local(ordinal = 0) BlockPos pos) {
        // CraftBukkit // Paper - fix wrong block state
        return org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.mob, pos, this.level.getBlockState(pos).getFluidState().createLegacyBlock(), !mobGriefing);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z", ordinal = 1))
    private boolean fission$eatDirtEvent(boolean mobGriefing, @Local(ordinal = 1) BlockPos below) {
        // CraftBukkit // Paper - Fix wrong block state
        return org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.mob, below, Blocks.DIRT.defaultBlockState(), !mobGriefing);
    }
}
