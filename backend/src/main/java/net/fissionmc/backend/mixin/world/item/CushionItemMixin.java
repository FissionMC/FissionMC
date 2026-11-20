package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CushionItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/CushionItem.java.patch (EntityPlaceEvent before the entity is added) */
@Mixin(CushionItem.class)
public abstract class CushionItemMixin {
    @Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), cancellable = true)
    private void paper$entityPlace(final UseOnContext context, final CallbackInfoReturnable<InteractionResult> cir, @Local final net.minecraft.world.entity.decoration.Cushion entity) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(context, entity).isCancelled()) {
            
            cir.setReturnValue(InteractionResult.FAIL);
        }
        // CraftBukkit end
    }
}
