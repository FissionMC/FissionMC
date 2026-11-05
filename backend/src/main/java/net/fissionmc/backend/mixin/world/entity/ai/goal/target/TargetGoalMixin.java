package net.fissionmc.backend.mixin.world.entity.ai.goal.target;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.bukkit.event.entity.EntityTargetEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/ai/goal/target/TargetGoal.java.patch (setTarget with reason) */
@Mixin(TargetGoal.class)
public abstract class TargetGoalMixin {
    @WrapOperation(method = "canContinueToUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$setTarget0(net.minecraft.world.entity.Mob mob, @Nullable LivingEntity target, Operation<Void> original) {
        mob.setTarget(target, EntityTargetEvent.TargetReason.CLOSEST_ENTITY); // CraftBukkit - reason
    }

    @WrapOperation(method = "stop", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$setTarget1(net.minecraft.world.entity.Mob mob, @Nullable LivingEntity target, Operation<Void> original) {
        mob.setTarget(target, EntityTargetEvent.TargetReason.FORGOT_TARGET); // CraftBukkit - reason
    }
}
