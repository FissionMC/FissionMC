package net.fissionmc.backend.mixin.advancements.triggers;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.function.Predicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/advancements/triggers/SimpleCriterionTrigger.java.patch (Perf: lazily create LootContext for criterions) */
@Mixin(SimpleCriterionTrigger.class)
public abstract class SimpleCriterionTriggerMixin {

    // LootContext playerContext = null; // EntityPredicate.createContext(player, player); // Paper - Perf: lazily create LootContext for criterions
    @WrapOperation(method = "trigger", at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/predicates/entity/EntityPredicate;createContext(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/storage/loot/LootContext;"))
    private LootContext fission$deferContext(ServerPlayer player, Entity entity, Operation<LootContext> original) {
        return null;
    }

    @WrapOperation(method = "trigger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;test(Ljava/lang/Object;)Z"))
    private boolean fission$lazyContext(LootItemCondition condition, Object context, Operation<Boolean> original,
                                        ServerPlayer player, Predicate<?> matcher, @Share("playerContext") LocalRef<LootContext> playerContext) {
        if (playerContext.get() == null) playerContext.set(EntityPredicate.createContext(player, player)); // Paper - Perf: lazily create LootContext for criterions
        return original.call(condition, playerContext.get());
    }
}
