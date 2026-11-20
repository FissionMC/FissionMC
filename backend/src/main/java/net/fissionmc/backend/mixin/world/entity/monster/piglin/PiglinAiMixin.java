package net.fissionmc.backend.mixin.world.entity.monster.piglin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paper: sources/net/minecraft/world/entity/monster/piglin/PiglinAi.java.patch
 * done: PiglinBarterEvent, EntityPickupItemEvent (onItemPickup moves behind it, see PiglinMixin). Not ported: custom barter/interest item lists (isBarterCurrency/isLovedItem(body) overloads), piglinsGuardChests.
 */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
    @Inject(method = "pickUpItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;stopWalking(Lnet/minecraft/world/entity/monster/piglin/Piglin;)V", shift = At.Shift.AFTER), cancellable = true)
    private static void paper$pickupEvent(final ServerLevel level, final Piglin body, final ItemEntity itemEntity, final CallbackInfo ci) {
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPickupItemEvent(body, itemEntity, itemEntity.getItem().is(Items.GOLD_NUGGET) ? 0 : itemEntity.getItem().getCount() - 1).isCancelled()) { // Paper
            ci.cancel();
            return;
        }
        body.onItemPickup(itemEntity); // Paper - moved from Piglin#pickUpItem - call prior to item entity modification
    }

    @WrapOperation(method = "stopHoldingOffHandItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;getBarterResponseItems(Lnet/minecraft/world/entity/monster/piglin/Piglin;)Ljava/util/List;"))
    private static List<ItemStack> paper$barterEvent(final Piglin body, final Operation<List<ItemStack>> original, @Local(ordinal = 0) final ItemStack itemStack) {
        // CraftBukkit start
        org.bukkit.event.entity.PiglinBarterEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPiglinBarterEvent(body, original.call(body), itemStack);
        if (event.isCancelled()) {
            return List.of(); // nothing is thrown
        }
        return event.getOutcome().stream().map(org.bukkit.craftbukkit.inventory.CraftItemStack::asNMSCopy).collect(java.util.stream.Collectors.toList());
        // CraftBukkit end
    }
}
