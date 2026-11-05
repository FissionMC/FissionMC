package net.fissionmc.backend.mixin.world.entity.ai.goal.target;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import org.bukkit.event.entity.EntityTargetEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/ai/goal/target/OwnerHurtByTargetGoal.java.patch (setTarget with reason) */
@Mixin(OwnerHurtByTargetGoal.class)
public abstract class OwnerHurtByTargetGoalMixin {
    @WrapOperation(method = "start", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$setTarget0(net.minecraft.world.entity.Mob mob, @Nullable LivingEntity target, Operation<Void> original) {
        mob.setTarget(target, EntityTargetEvent.TargetReason.TARGET_ATTACKED_OWNER); // CraftBukkit - reason
    }
}
