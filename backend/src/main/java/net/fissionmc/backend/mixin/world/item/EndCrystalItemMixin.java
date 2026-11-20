package net.fissionmc.backend.mixin.world.item;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/item/EndCrystalItem.java.patch (EntityPlaceEvent before the entity is added) */
@Mixin(EndCrystalItem.class)
public abstract class EndCrystalItemMixin {
    @Inject(method = "useOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), cancellable = true)
    private void paper$entityPlace(final UseOnContext context, final CallbackInfoReturnable<InteractionResult> cir, @Local final net.minecraft.world.entity.boss.enderdragon.EndCrystal entity) {
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(context, entity).isCancelled()) {
            if (context.getPlayer() != null) context.getPlayer().containerMenu.sendAllDataToRemote(); // Paper - Fix inventory desync
            cir.setReturnValue(InteractionResult.FAIL);
        }
        // CraftBukkit end
    }
}
