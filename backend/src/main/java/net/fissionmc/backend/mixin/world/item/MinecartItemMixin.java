package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/MinecartItem.java.patch (EntityPlaceEvent before the entity is added) */
@Mixin(MinecartItem.class)
public abstract class MinecartItemMixin {
    @Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), cancellable = true)
    private void paper$entityPlace(final UseOnContext context, final CallbackInfoReturnable<InteractionResult> cir, @Local final net.minecraft.world.entity.vehicle.minecart.AbstractMinecart entity) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(context, entity).isCancelled()) {
            if (context.getPlayer() != null) context.getPlayer().containerMenu.forceHeldSlot(context.getHand()); // Paper - Fix inventory desync
            cir.setReturnValue(InteractionResult.FAIL);
        }
        // CraftBukkit end
    }
}
