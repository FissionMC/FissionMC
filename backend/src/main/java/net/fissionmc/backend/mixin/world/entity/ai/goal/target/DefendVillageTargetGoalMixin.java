package net.fissionmc.backend.mixin.world.entity.ai.goal.target;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.DefendVillageTargetGoal;
import org.bukkit.event.entity.EntityTargetEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/entity/ai/goal/target/DefendVillageTargetGoal.java.patch (setTarget with reason) */
@Mixin(DefendVillageTargetGoal.class)
public abstract class DefendVillageTargetGoalMixin {
    @WrapOperation(method = "start", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/golem/IronGolem;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void fission$setTarget0(net.minecraft.world.entity.animal.golem.IronGolem mob, @Nullable LivingEntity target, Operation<Void> original) {
        mob.setTarget(target, EntityTargetEvent.TargetReason.DEFEND_VILLAGE); // CraftBukkit - reason
    }
}
